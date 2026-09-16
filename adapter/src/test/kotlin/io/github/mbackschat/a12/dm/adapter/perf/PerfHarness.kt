package io.github.mbackschat.a12.dm.adapter.perf

import io.github.mbackschat.a12.dm.adapter.laws.RuntimeLaws
import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.conformance.KernelComputeProjection
import io.github.mbackschat.a12.dm.interpreter.conformance.Placement
import io.github.mbackschat.a12.dm.interpreter.conformance.PlacementKind
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.gen.PlacementSink
import io.github.mbackschat.a12.dm.interpreter.gen.asSink
import io.github.mbackschat.a12.dm.interpreter.model.ComputeOutcome
import io.github.mbackschat.a12.dm.interpreter.model.Computed
import io.github.mbackschat.a12.dm.interpreter.model.Message
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.MsgType
import io.github.mbackschat.a12.dm.interpreter.model.UnsupportedRule
import java.lang.management.ManagementFactory
import java.util.Locale

/**
 * The reusable **against-kernel performance harness** (INTERPRETER-PERFORMANCE.md). It does double duty: it
 * **gates** O(N²)-class regressions in the group machinery *and* it's how we **find** the hotspots. The unit
 * is a whole-document evaluation — warmup + repeats + median + ratio is the right rigor for it.
 *
 * **Two layers, mirroring "prepare the model once; reuse across runs" (the spec's architecture direction):**
 * - [PerfEngine.build] builds the reusable engine for a scenario ONCE — the interpreter's [DmInterpreter], the
 *   kernel's codegen+rt service. This is the artifact a real consumer keeps while values change.
 * - [ReusableEngine.instance] materializes one document at scale `N` (and a `variant`, for the repeated case),
 *   OUTSIDE the timed region, returning a [RunInstance] whose [RunInstance.run] is the timed evaluation.
 *
 * The central reuse win: a [PerfScenario] describes its instance **once** (a `(PlacementSink, N, variant)`
 * generator), and each engine materializes it its own way — the kernel into a [RuntimeLaws.Doc] -> IDocument,
 * the interpreter into the general [Document]. No copy-pasted lockstep build loops.
 *
 * **Two measurement modes:**
 * - [runScenario] — single-document throughput: time one instance evaluated repeatedly (the O(N²) gate).
 * - [runRepeated] — **repeated eval over slightly-different documents** (form-input / batch): the engine built
 *   once, M variant instances run through it. This is what the kernel's codegen + per-run caches are tuned for,
 *   and the mode where the kernel's compiled rules most out-run the interpreter's per-call tree-WALK (parsing is
 *   one-and-done — cached in `ParsedModel` at construction; the #31 re-parse cost is retired).
 *
 * **Correctness first (a named non-negotiable):** before timing, comparison modes assert complete validation
 * signatures (`code|type|indexed-pointer`) or computation signatures (`indexed-pointer|outcome|value`) across
 * every eligible engine. Every repeated variant is checked, and every actual interpreter-only instance retains
 * its outcome-bearing eligibility check. A fast-but-wrong or silently partial path is no win.
 *
 * **⭐ Code generation is NEVER inside the timed region.** The kernel's real architecture is **generate code
 * ONCE, ahead of validation, then validate/compute many documents against it** (model-load time → codegen →
 * cached in its `IModelCodeCache`; the steady state is per-document validation, codegen amortized to zero).
 * Measuring codegen would be measuring a fiction the kernel never pays per-document. So the harness **primes it
 * out**: the kernel generates code on its FIRST `validateFull` (cached for the engine's whole life), and every
 * timed region is preceded by a correctness `run()` **plus** warmup — so the codegen call is always discarded.
 * The timed numbers are **steady-state validation against already-generated code**, the realistic production
 * cost. (This is also why the kernel's per-call time only JIT-warms downward over the first ~dozen calls — that
 * is the Groovy validator warming, NOT recodegen, which is one-and-done.)
 *
 * Lives in `:adapter` (the only module with the kernel); pure engine — NO dmtool / CLI / process / native, just
 * the `DmInterpreter` + `Document` API and `RuntimeLaws`.
 */

/** Which operation a scenario times: full validation, or computation (`compute()` — the B7 path). */
enum class PerfOp { VALIDATE, COMPUTE }

/**
 * One performance scenario: the SAME logical model expressed for BOTH engines + a scale-parameterized,
 * variant-parameterized instance generator. The two model representations describe the same rule(s)/
 * computation(s); [PerfCatalog] builds them from one set of params so they cannot drift.
 */
class PerfScenario private constructor(
    val name: String,
    /** The base DM-JSON with the scenario's rule(s) injected — the kernel's model (`RuntimeLaws.withRule`). */
    val kernelModel: String,
    /** The same model as a [ModelDef] — the interpreter's model. */
    val interpModel: ModelDef,
    /** Whether this scenario times `validateFull` or `compute` (default validate). */
    val op: PerfOp = PerfOp.VALIDATE,
    /** Builds the prepared interpreter, including any scenario-required customization registries. */
    val interpreterFactory: (ModelDef) -> DmInterpreter,
    /** The locale both interpreter and kernel engines receive for this scenario. */
    val locale: String = "en_US",
    /** Clock and callbacks supplied to both build-once kernel strategies on every evaluation. */
    internal val kernelConfig: KernelPerfConfig,
    /** Stable runtime-binding descriptors included in every scenario fingerprint. */
    internal val runtimeIdentity: Map<String, String>,
    /** False only for interpreter-only preflight tests that cannot make a kernel comparison. */
    private val comparativeBindings: Boolean,
    /** Explicit metadata for parameterized, non-certified scenarios such as synthesized profiles. */
    internal val diagnosticMetadata: DiagnosticScenarioMetadata?,
    private val identityBinding: ScenarioBinding?,
    /**
     * Build the instance at scale [n], perturbed by [variant], into [sink]. `variant = 0` is the canonical
     * instance; a non-zero variant shifts a value so the document differs slightly (mimicking a form edit /
     * the next batch record) — used by [PerfHarness.runRepeated].
     */
    val instance: (sink: PlacementSink, n: Int, variant: Int) -> Unit,
) {
    constructor(
        name: String,
        kernelModel: String,
        interpModel: ModelDef,
        op: PerfOp = PerfOp.VALIDATE,
        locale: String = "en_US",
        instance: (sink: PlacementSink, n: Int, variant: Int) -> Unit,
    ) : this(
        name, kernelModel, interpModel, op,
        PerfRuntimeBindings.DEFAULT::interpreter,
        locale,
        PerfRuntimeBindings.DEFAULT.kernelConfig(),
        PerfRuntimeBindings.DEFAULT.identity,
        comparativeBindings = true,
        diagnosticMetadata = null,
        identityBinding = null,
        instance,
    )

    init {
        require(locale.isNotBlank()) { "$name: locale must not be blank" }
    }

    /** The reviewed, content-bound identity. Available after [ScenarioId.bind]. */
    val identity: ScenarioBinding
        get() = checkNotNull(identityBinding) { "$name has no reviewed scenario identity" }

    internal fun withIdentity(identity: ScenarioBinding): PerfScenario =
        PerfScenario(
            name, kernelModel, interpModel, op, interpreterFactory, locale, kernelConfig, runtimeIdentity,
            comparativeBindings, diagnosticMetadata, identity, instance,
        )

    /** Derive both interpreter and kernel runtime configuration from one immutable source. */
    internal fun withRuntimeBindings(bindings: PerfRuntimeBindings): PerfScenario = PerfScenario(
        name, kernelModel, interpModel, op, bindings::interpreter, locale, bindings.kernelConfig(), bindings.identity,
        comparativeBindings = true, diagnosticMetadata, identityBinding, instance,
    )

    /** Interpreter-only seam for support/preflight guards. Comparative methods reject this scenario. */
    internal fun withInterpreterOnlyFactory(
        contractId: String,
        factory: (ModelDef) -> DmInterpreter,
    ): PerfScenario {
        require(contractId.isNotBlank()) { "interpreter-only runtime contract id must not be blank" }
        return PerfScenario(
            name, kernelModel, interpModel, op, factory, locale, PerfRuntimeBindings.DEFAULT.kernelConfig(),
            mapOf("interpreter-only" to contractId), comparativeBindings = false, diagnosticMetadata,
            identityBinding, instance,
        )
    }

    internal fun withDiagnosticMetadata(metadata: DiagnosticScenarioMetadata): PerfScenario = PerfScenario(
        name, kernelModel, interpModel, op, interpreterFactory, locale, kernelConfig, runtimeIdentity,
        comparativeBindings, metadata, identityBinding, instance,
    )

    internal fun requireComparativeBindings() {
        check(comparativeBindings) {
            "$name: comparative timing requires one PerfRuntimeBindings source for interpreter and kernel"
        }
    }

    internal fun measurementLabel(n: Int, variants: List<Int> = listOf(0)): String {
        val identity = identityBinding?.also {
            check(it.n == n) { "${it.id.id}@${it.id.version}: bound at N=${it.n}, measurement requested N=$n" }
        }?.let { ScenarioIdentity(it.id, n, it.fingerprint, emptyList()) }
            ?: diagnosticIdentity(n, observed = emptyList(), variants)
        return "${identity.label} fingerprint=${identity.fingerprint}"
    }

    internal fun completeIdentity(n: Int, observed: List<Int>, variants: List<Int>): ScenarioIdentity =
        identityBinding?.also {
            check(it.n == n) { "${it.id.id}@${it.id.version}: bound at N=${it.n}, run requested N=$n" }
            check(it.id.generator.variants == variants) {
                "${it.id.id}@${it.id.version}: bound variants=${it.id.generator.variants}, run requested variants=$variants"
            }
        }?.complete(observed) ?: diagnosticIdentity(n, observed, variants)
}

/** Full semantic result of one instance. [unsupported] is non-empty only for the interpreter leg. */
data class PerfOutcome(val signatures: List<String>, val unsupported: List<UnsupportedRule> = emptyList())

/** A timed runnable bound to ONE materialized instance — built outside timing; [run] is the timed op. */
interface RunInstance {
    /** The full outcome-bearing semantic result, used before timing. */
    fun outcome(): PerfOutcome

    /** The timed projection. Full signatures are materialized inside the clocked call; unsupported work can
     *  never be projected away, even when a diagnostic caller reaches this lower-level surface directly. */
    fun run(): List<String> {
        val result = outcome()
        check(result.unsupported.isEmpty()) {
            "RunInstance cannot project an unsupported outcome: " +
                result.unsupported.joinToString { "${it.rulePath} (${it.reason})" }
        }
        return result.signatures
    }
}

/** A reusable engine for a scenario, built ONCE — then many instances run through it (the model-reuse layer). */
interface ReusableEngine {
    val label: String
    /** Materialize the instance at scale [n] with [variant] applied (OUTSIDE timing); its [RunInstance] is timed. */
    fun instance(n: Int, variant: Int = 0): RunInstance
}

/** Builds the reusable engine for a scenario — the interpreter's model parse / the kernel's codegen, once. */
interface PerfEngine {
    val label: String
    fun build(scenario: PerfScenario): ReusableEngine
}

/** The interpreter engine: [DmInterpreter] built once, each [ReusableEngine.instance] a general [Document]. */
object InterpreterPerfEngine : PerfEngine {
    override val label = "interp"
    override fun build(scenario: PerfScenario): ReusableEngine {
        val model: ModelDef = scenario.interpModel
        val interp = scenario.interpreterFactory(model)   // built once; rules parsed once + cached at construction (ParsedModel — #31 re-parse retired; per-call cost is the tree-walk)
        return object : ReusableEngine {
            override val label = "interp"
            override fun instance(n: Int, variant: Int): RunInstance {
                val doc = model.newDocument()
                val placements = ArrayList<Placement>()
                scenario.instance(CapturingDocumentSink(doc.asSink(), placements), n, variant)
                val computeProjection = if (scenario.op == PerfOp.COMPUTE) {
                    KernelComputeProjection.prepare(doc, model)
                } else {
                    null
                }
                return object : RunInstance {
                    override fun outcome(): PerfOutcome = when (scenario.op) {
                        PerfOp.VALIDATE -> interp.validate(doc, scenario.locale).let { result ->
                            PerfOutcome(result.messages.map(::validationSignature).sorted(), result.unsupported)
                        }
                        PerfOp.COMPUTE -> PerfOutcome(
                            checkNotNull(computeProjection)
                                .project(interp.compute(doc, scenario.locale).map(::computationSignature))
                                .sorted(),
                        )
                    }
                }
            }
        }
    }
}

/** The Groovy kernel engine: the build-once `RuntimeLaws.KernelEngine` (Groovy codegen once), each instance an IDocument. */
object KernelPerfEngine : PerfEngine {
    override val label = "groovy"
    override fun build(scenario: PerfScenario): ReusableEngine {
        val engine = RuntimeLaws.kernelEngine(scenario.kernelModel)   // model deserialize + expand + Groovy codegen, once
        val locale = scenario.javaLocale()
        val config = scenario.kernelConfig
        return object : ReusableEngine {
            override val label = "groovy"
            override fun instance(n: Int, variant: Int): RunInstance {
                val builder = RuntimeLaws.doc()
                scenario.instance(KernelDocSink(builder), n, variant)
                val instance = engine.instance(builder)   // build the IDocument once, outside timing
                return object : RunInstance {
                    override fun outcome(): PerfOutcome = PerfOutcome(
                        when (scenario.op) {
                            PerfOp.VALIDATE -> engine.validationSignaturesSorted(
                                instance, locale, config.freshClock(), config.customConditions,
                                config.customFieldTypeFactory)
                            PerfOp.COMPUTE -> engine.computationSignaturesSorted(
                                instance, locale, config.freshClock(), config.customConditions,
                                config.customFieldTypeFactory)
                        },
                    )
                }
            }
        }
    }
}

/**
 * The **Java** kernel engine: the kernel's OWN generated validator compiled as Java (build-once
 * [RuntimeLaws.kernelJavaEngine] — generate Java source + in-memory compile + static rt-service, once), each
 * instance an IDocument. This is the *Groovy-tax-free* baseline (INTERPRETER-PERFORMANCE.md "the kernel
 * baseline: Groovy vs Java"): the SAME validation logic the Groovy path runs, minus the Groovy execution tax.
 */
object KernelJavaPerfEngine : PerfEngine {
    override val label = "java"
    override fun build(scenario: PerfScenario): ReusableEngine {
        val engine = RuntimeLaws.kernelJavaEngine(scenario.kernelModel)   // codegen + in-memory compile, once
        val locale = scenario.javaLocale()
        val config = scenario.kernelConfig
        return object : ReusableEngine {
            override val label = "java"
            override fun instance(n: Int, variant: Int): RunInstance {
                val builder = RuntimeLaws.doc()
                scenario.instance(KernelDocSink(builder), n, variant)
                val instance = engine.instance(builder)   // build the IDocument once, outside timing
                return object : RunInstance {
                    override fun outcome(): PerfOutcome = PerfOutcome(
                        when (scenario.op) {
                            PerfOp.VALIDATE -> engine.validationSignaturesSorted(
                                instance, locale, config.freshClock(), config.customConditions,
                                config.customFieldTypeFactory)
                            PerfOp.COMPUTE -> engine.computationSignaturesSorted(
                                instance, locale, config.freshClock(), config.customConditions,
                                config.customFieldTypeFactory)
                        },
                    )
                }
            }
        }
    }
}

private fun validationSignature(message: Message): String =
    "${message.code}|${if (message.type == MsgType.OMISSION) "OMISSION_ERROR" else "VALUE_ERROR"}|${message.errorPath}"

private fun computationSignature(computed: Computed): String {
    val pointer = io.github.mbackschat.a12.dm.interpreter.eval.formatPointer(computed.path, computed.indices)
    return when (computed.outcome) {
        ComputeOutcome.VALUE -> "$pointer|VALUE|${computed.value ?: ""}"
        ComputeOutcome.CLEARED -> "$pointer|CLEARED"
        ComputeOutcome.ERRORED -> "$pointer|ERRORED|${computed.value ?: ""}"
    }
}

private fun PerfScenario.javaLocale(): Locale =
    Locale.forLanguageTag(locale.replace('_', '-'))

/** Feeds a [PerfScenario]'s placements into a [RuntimeLaws.Doc] — shared by both kernel engines (Groovy + Java)
 *  and by the entry-point guards, which build the same document outside this harness ([EntryPointPerfTest]). */
internal class KernelDocSink(private val doc: RuntimeLaws.Doc) : PlacementSink {
    override fun group(path: String, vararg reps: Int) { doc.group(path, *reps) }
    override fun field(path: String, value: String, vararg reps: Int) { doc.field(path, value, *reps) }
    override fun emptyField(path: String, vararg reps: Int) { doc.emptyField(path, *reps) }
}

/** Captures source placements while building [delegate], outside timing, so the immutable kernel-delta input
 * lookup can be prepared once with the reusable instance (IF199). */
private class CapturingDocumentSink(
    private val delegate: PlacementSink,
    private val placements: MutableList<Placement>,
) : PlacementSink {
    override fun group(path: String, vararg reps: Int) {
        delegate.group(path, *reps)
        placements += Placement(PlacementKind.GROUP, path, reps.toList())
    }

    override fun field(path: String, value: String, vararg reps: Int) {
        delegate.field(path, value, *reps)
        placements += Placement(PlacementKind.FIELD, path, reps.toList(), value)
    }

    override fun emptyField(path: String, vararg reps: Int) {
        delegate.emptyField(path, *reps)
        placements += Placement(PlacementKind.EMPTY, path, reps.toList())
    }
}

/** Wall-clock distribution (ms) of a timed operation — robust stats, not a single cold number. */
data class Stats(val medianMs: Double, val minMs: Double, val p90Ms: Double)

/**
 * What one instance's steady-state warm may spend, and when it decides the warm curve has flattened.
 *
 * The two caps guard opposite shapes and both are needed: without [maxRuns] a sub-millisecond instance keeps
 * improving for thousands of runs before it flattens, and without [maxMillis] a 200 ms kernel evaluation spends
 * a minute reaching the run cap.
 */
internal data class WarmUpBudget(
    /** Runs per probe; the batch MEDIAN is what the flattening rule reads, so one slow run cannot end a warm. */
    val batch: Int = 3,
    /**
     * Hard stop on one instance's total warm time — the guard for an expensive scenario.
     *
     * **Why not 1 s, which it was until 2026-08-12.** At 1 s exactly one instance in a full gate run ran out of
     * budget instead of flattening: `CO-correlation`'s dynamic-Groovy arm at N=800, ended by this cap after 9 runs
     * at ~110 ms each. That bias is not neutral — a kernel measurement still falling reads HIGHER than its settled
     * cost, so `interp/groovy` reads LOWER and the margin on the estate's tightest row looks better than it is. The
     * cap has to be loose enough that the dearest engine can reach two consecutive flat batches.
     *
     * **8 s, and the number is the second measurement rather than the first.** At 4 s that arm flattened, and a
     * DIFFERENT one took its place in one of three runs: `Number index validate`'s dynamic-Groovy arm at N=2,000,
     * the dearest instance in the estate at ~266 ms per evaluation, ended by the cap after 15 runs. It flattened
     * inside 4 s in the other two runs, so it sits exactly on that boundary and noise decides — which is what makes
     * a factor of headroom the right answer rather than another boundary value. The [maxRuns] cap still bounds a
     * cheap instance, so this only ever spends more on the instances whose coldness distorts a ratio most, and it
     * spends it only when the curve has not flattened.
     */
    val maxMillis: Double = 8_000.0,
    /** Hard stop on one instance's total warm runs — the guard for a cheap one. */
    val maxRuns: Int = 60,
    /** A batch within this factor of the previous counts as flat: the curve has stopped improving. */
    val flatRatio: Double = 1.05,
    /**
     * Consecutive flat batches the warm must see before it believes them.
     *
     * One is not enough, measured: at `flatBatches = 1` the warm stopped after ~8 runs per instance and generated
     * Java at N=200 was still falling 1.19x across the pairs afterwards. A warm curve is noisy at batch
     * granularity, so a single flat-looking probe is one observation and two are a confirmation — the same
     * asymmetry the comparative bars apply to a breach, pointed the other way.
     */
    val flatBatches: Int = 2,
) {
    init {
        require(batch > 0 && batch % 2 == 1) { "batch must be a positive odd number so it has a median, was $batch" }
        require(maxMillis > 0) { "maxMillis must be positive, was $maxMillis" }
        require(maxRuns >= batch) { "maxRuns ($maxRuns) must allow at least one batch ($batch)" }
        require(flatRatio > 1.0) { "flatRatio must be greater than 1, was $flatRatio" }
        require(flatBatches > 0) { "flatBatches must be positive, was $flatBatches" }
    }
}

/**
 * The stop rule for a steady-state warm: keep going while the per-run cost is still improving, stop at the first
 * flat batch or at either cap.
 *
 * Separated from the clock so the rule is checked on scripted numbers ([SteadyStateWarmUpTest]), which is how
 * every other acceptance rule in this estate is verified.
 */
/**
 * Why a warm ended. Only [FLATTENED] means the instance actually reached steady state; the two caps mean the
 * budget ran out first, which for the most expensive engine in the estate is the likeliest outcome and exactly
 * the case a comment claiming "every instance reaches steady state" would paper over.
 */
internal enum class WarmStop(val label: String) {
    FLATTENED("flattened"),
    RUN_CAP("run-cap"),
    TIME_CAP("time-cap"),
}

internal class SteadyStateWarmUp(private val budget: WarmUpBudget) {

    private var previousMs: Double? = null
    private var flatRun = 0

    /** Set once [hasSettled] returns true; null while the warm is still running. */
    var stop: WarmStop? = null
        private set

    /** Runs the caller has been asked to pay for so far. */
    var runs: Int = 0
        private set

    /** Time those runs took, derived from the batch medians rather than clocked separately. */
    var elapsedMs: Double = 0.0
        private set

    /** Feed one batch's median run cost; answer whether the warm has finished. */
    fun hasSettled(batchMedianMs: Double): Boolean {
        require(batchMedianMs.isFinite() && batchMedianMs > 0.0) {
            "a warm batch median must be finite and positive, was $batchMedianMs"
        }
        runs += budget.batch
        elapsedMs += batchMedianMs * budget.batch
        // A batch that got WORSE counts as flat too: whatever moved it, it is no longer JIT warming, and warming
        // through noise would spend the budget chasing it.
        val improvement = previousMs?.div(batchMedianMs)
        flatRun = if (improvement != null && improvement < budget.flatRatio) flatRun + 1 else 0
        previousMs = batchMedianMs
        // Flattening is checked FIRST so a warm that settled on the same batch that exhausted a cap is reported
        // as settled — the caps are the fallback, not the verdict.
        stop = when {
            flatRun >= budget.flatBatches -> WarmStop.FLATTENED
            runs >= budget.maxRuns -> WarmStop.RUN_CAP
            elapsedMs >= budget.maxMillis -> WarmStop.TIME_CAP
            else -> null
        }
        return stop != null
    }
}

/** The result of the IF137-ratified [PerfHarness.pairedGrowth] estimator: the median within-pair growth [ratio]
 *  and a representative [smallValue]/[largeValue] (the medians across pairs) for the log line.
 *
 *  **The two representative values carry the LANE's unit, which is why they are not named `*Ms`** (renamed
 *  2026-09-17, when a byte lane became the second consumer). The quantity under assertion is the dimensionless
 *  [ratio]; the values exist so a breach names the observations it was decided on, and a caller passes its own
 *  `unit` to whichever seam prints them. Timing lanes hold milliseconds and `:adapter`'s memory lane holds bytes.
 *  [PairedAcceptance.perUnitMs] keeps its millisecond-specific name deliberately: it divides these values by a
 *  scale to get a per-unit cost, and only the timing curves construct the `EngineCurve` it reads.
 *
 *  [smallN]/[largeN] carry the scales the two medians were measured at, so a caller that wants to ANCHOR them does
 *  not have to thread the scales separately and cannot pair a median with the wrong one. They default to zero for
 *  the harness's own unit tests, which script the ratios rather than measuring them. */
data class Growth(
    val ratio: Double,
    val smallValue: Double,
    val largeValue: Double,
    val smallN: Int = 0,
    val largeN: Int = 0,
)

/** One paired REUSE comparison: the stateless arm, the stateful arm that answers identically, and their ratio.
 *
 *  The ratio is the median of the WITHIN-PAIR ratios rather than the quotient of the two medians beside it — a
 *  quotient of medians is not the median of quotients — so the two durations are representative and the ratio is
 *  the measurement. Both are carried because a breach reported as a bare ratio does not say which arm moved. */
data class Reuse(val ratio: Double, val statelessMs: Double, val statefulMs: Double)

/** A complete same-run N/4N comparison, paired independently for all three engines. */
data class PairedPerfCurve(
    val small: PerfRow,
    val large: PerfRow,
    val interpreterGrowth: Growth,
    val groovyGrowth: Growth,
    val javaGrowth: Growth,
)

/**
 * One single-document scenario row: the interpreter's stats at scale [n] vs BOTH kernel baselines — [groovy]
 * (dynamic, the production path) and [java] (the kernel's own validator compiled as Java, the Groovy-tax-free
 * baseline). The **[ratioJava]** is the *fair* algorithmic comparison; [ratioGroovy] is tax-inflated; [groovyTax]
 * is how much slower Groovy execution is than Java for this scenario.
 */
data class PerfRow(
    val identity: ScenarioIdentity, val n: Int, val firings: Int,
    val interp: Stats, val groovy: Stats?, val java: Stats?,
) {
    val scenario: String get() = identity.id.id
    /** interp_median / groovy_median — tax-inflated (Groovy is slower than Java; read with the fairness caveat). */
    val ratioGroovy: Double? get() = groovy?.let { interp.medianMs / it.medianMs }
    /** interp_median / java_median — the FAIR algorithmic ratio (kernel logic, Groovy tax removed). */
    val ratioJava: Double? get() = java?.let { interp.medianMs / it.medianMs }
    /** groovy_median / java_median — the Groovy execution tax (≥ 1 means Groovy is slower than the same logic in Java). */
    val groovyTax: Double? get() = if (groovy != null && java != null) groovy.medianMs / java.medianMs else null
}

/** One repeated-eval row: engine built ONCE, [variants] varying instances looped — PER-EVAL stats + the two ratios. */
data class RepeatedRow(
    val identity: ScenarioIdentity, val n: Int, val variants: Int,
    val interp: Stats, val groovy: Stats?, val java: Stats?,
) {
    val scenario: String get() = identity.id.id
    val ratioGroovy: Double? get() = groovy?.let { interp.medianMs / it.medianMs }
    val ratioJava: Double? get() = java?.let { interp.medianMs / it.medianMs }
    val groovyTax: Double? get() = if (groovy != null && java != null) groovy.medianMs / java.medianMs else null
}

object PerfHarness {

    private const val SIGNATURE_SAMPLE_LIMIT = 10
    private const val SIGNATURE_DISPLAY_LIMIT = 240

    /** `-Dperf.trace=true` prints every per-pair observation a parity row reduces away ([ParitySample.trace]). */
    private val TRACE_PER_PAIR: Boolean = System.getProperty("perf.trace") == "true"

    private data class SignatureSample(val signature: String, val occurrences: Int)

    private data class SignatureDelta(
        val occurrences: Int,
        val distinct: Int,
        val samples: List<SignatureSample>,
    )

    /**
     * Emit a LIVE progress line during a long perf run. Gradle buffers a test's captured stdout until the whole
     * task ENDS, so a slow multi-scenario gate looks frozen with no output — this writes to a **tailable file**
     * with an immediate flush (append-per-call, so nothing is buffered), in addition to `println` (which still
     * lands in the test report). Watch a run with `tail -f adapter/build/perf-progress.log`. The file is
     * truncated once per JVM (first call), so each run starts clean.
     */
    private val progressFile: java.io.File by lazy {
        java.io.File("build/perf-progress.log").apply { parentFile?.mkdirs(); writeText("") }
    }

    fun progress(msg: String) {
        println(msg)
        runCatching { progressFile.appendText(msg + "\n") }
    }

    /** `id@version fingerprint=<sha256>` for every reviewed or diagnostic measurement line. */
    fun measurementLabel(scenario: PerfScenario, n: Int, variants: List<Int> = listOf(0)): String =
        scenario.measurementLabel(n, variants)

    /**
     * The same `id@version fingerprint=<sha256>` label for a lane that builds its **own** fixture rather than
     * running a [PerfScenario].
     *
     * Several routine-tier classes construct a model and document inline — a charset document, an IBAN custom
     * type, an antichain of wildcard masks — so they have no scenario to derive an identity from. They still need
     * one, for the reason every key carries a fingerprint: the identity must **change when the measured work
     * changes**, so that editing a fixture drops its anchor rather than silently comparing new work against an old
     * number. [content] is therefore whatever describes the work — the shape parameters, the scale, the rule —
     * and the caller is responsible for it naming everything it varies.
     *
     * `PerfAnchorResourceTest` pins the key grammar, and it caught the first version of these lanes: they were
     * given prose identities (`"charset BMP grapheme rows n=8000"`), which read fine and carry no fingerprint at
     * all, so a fixture edit would have kept the anchor and compared different work against it.
     */
    /**
     * Re-fingerprint an existing `id@version fingerprint=<sha256>` label for a batched lane, preserving the key
     * grammar `PerfAnchorResourceTest` pins while guaranteeing the key differs from the unbatched one.
     */
    private fun rebatchedLabel(label: String, batch: Int): String {
        val id = label.substringBefore('@')
        return fingerprintedLabel(id, "$label|batch=$batch")
    }

    fun fingerprintedLabel(id: String, content: String): String {
        require(id.isNotBlank() && ' ' !in id && '|' !in id) { "a lane id must be a single unspaced token: '$id'" }
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(content.toByteArray())
        return "$id@1 fingerprint=" + digest.joinToString("") { "%02x".format(it) }
    }

    /** Time [block], streaming a `<label> …` line before and the `<label> = <ms>` after (live [progress]). */
    inline fun <T> step(label: String, block: () -> T): T {
        progress("[perf] $label …")
        val t = System.nanoTime()
        val r = block()
        progress("[perf] $label — done in %.1fs".format(Locale.ROOT, (System.nanoTime() - t) / 1e9))
        return r
    }

    /**
     * Median (+ min + p90) wall-clock of [block] over [iters] timed runs after [warmup] discarded runs. The
     * warmup must be **generous**: the kernel's per-call time DECREASES monotonically over the first ~dozen
     * calls (JVM JIT warming the Groovy-compiled rules — NOT per-call codegen, which is cached), so too few
     * warmups *overstates* the kernel and the ratio. Never time a cold run.
     */
    fun measure(warmup: Int, iters: Int, block: () -> Unit): Stats {
        repeat(warmup) { block() }
        val times = DoubleArray(iters) { val t = System.nanoTime(); block(); (System.nanoTime() - t) / 1e6 }
        return stats(times)
    }

    /**
     * Median current-thread CPU time for a single-threaded algorithmic guard. Unlike [measure], this excludes
     * time while another Gradle test fork preempts the measuring JVM. Use it only for CPU-work ratios and growth
     * floors; user-visible latency and cross-engine throughput remain wall-clock observations.
     */
    fun measureCurrentThreadCpu(warmup: Int, iters: Int, block: () -> Unit): Stats {
        require(warmup >= 0) { "warmup must not be negative" }
        require(iters > 0) { "iters must be positive" }
        val bean = ManagementFactory.getThreadMXBean()
        require(bean.isCurrentThreadCpuTimeSupported) { "current-thread CPU time is unavailable on this JVM" }
        if (!bean.isThreadCpuTimeEnabled) bean.isThreadCpuTimeEnabled = true
        repeat(warmup) { block() }
        val times = DoubleArray(iters) {
            val start = bean.currentThreadCpuTime
            require(start >= 0L) { "current-thread CPU time is unavailable on this JVM" }
            block()
            val elapsed = bean.currentThreadCpuTime - start
            require(elapsed > 0L) { "current-thread CPU observation must be positive, got ${elapsed}ns" }
            elapsed / 1e6
        }
        return stats(times)
    }

    /** Cap on the diagnostic document [preflight] materializes. Value-independent support is proved by the
     *  prepared-model audit; exact-N and per-variant outcome checks separately catch data-sensitive callback
     *  failures. The capped sample therefore stays cheap without pretending to prove later rows. */
    private const val PREFLIGHT_MAX_ROWS = 200

    private fun preparedAudit(scenario: PerfScenario) {
        val unsupported = scenario.interpreterFactory(scenario.interpModel).supportAudit()
        check(unsupported.isEmpty()) {
            "${scenario.name} (prepared-model audit): refusing to time — ${unsupported.size} unsupported construct(s) " +
                "would be a timed fiction: " +
                unsupported.joinToString(prefix = "[", postfix = "]") { "${it.rulePath} (${it.reason})" }
        }
    }

    private fun requireEligible(scenario: PerfScenario, n: Int, variant: Int, outcome: PerfOutcome) {
        check(outcome.unsupported.isEmpty()) {
            "${scenario.name} (N=$n, variant=$variant): refusing to time — " +
                outcome.unsupported.joinToString(prefix = "[", postfix = "]") {
                    "${it.rulePath} (${it.reason})"
                }
        }
    }

    /** Evaluate one exact materialized interpreter instance outside every clock and require full eligibility. */
    private fun exactEligibleOutcome(
        scenario: PerfScenario,
        n: Int,
        variant: Int = 0,
        engine: ReusableEngine = InterpreterPerfEngine.build(scenario),
    ): PerfOutcome {
        val outcome = try {
            engine.instance(n, variant).outcome()
        } catch (e: io.github.mbackschat.a12.dm.interpreter.UnsupportedConstructException) {
            throw IllegalStateException(
                "${scenario.name} (N=$n, variant=$variant): refusing to time — ${e.message}", e)
        }
        requireEligible(scenario, n, variant, outcome)
        return outcome
    }

    /**
     * **Semantic preflight (IG77 → IF156): no sample times a fiction.** Before any timing, build the interpreter
     * for [scenario], materialize its canonical instance at a cost-capped scale ([PREFLIGHT_MAX_ROWS]), and assert
     * on the **outcome-bearing** [DmInterpreter.validate] API that nothing is unsupported. `validate` surfaces both
     * an unsupported **rule** and an unsupported **computation** (it runs the implicit computation-validity checks),
     * so this one uniform check covers VALIDATE and COMPUTE scenarios alike. This is the explicit before-timing
     * proof INTERPRETER-PERFORMANCE.md mandates ("Measure AGAINST the kernel"): a silently-unsupported construct
     * would make the interpreter look fast by doing **less** work than the kernel, so the harness REFUSES to time
     * it (naming the construct) rather than relying on `validateFull` to throw incidentally mid-timed-run. The
     * tri-engine outcome agreement (interp == groovy == java) is asserted separately, at the timed N, in
     * [runScenario]/[runRepeated].
     */
    fun preflight(scenario: PerfScenario, n: Int) {
        preparedAudit(scenario)
        val sampleN = n.coerceAtMost(PREFLIGHT_MAX_ROWS)
        val outcome = InterpreterPerfEngine.build(scenario).instance(sampleN, 0).outcome()
        requireEligible(scenario, sampleN, 0, outcome)
    }

    /** Check every actual repeated-eval variant on the outcome-bearing interpreter API before timing any loop. */
    fun preflightVariants(scenario: PerfScenario, n: Int, variants: Int) {
        require(variants > 0) { "variants must be positive" }
        preparedAudit(scenario)
        val engine = InterpreterPerfEngine.build(scenario)
        for (variant in 0 until variants) {
            val outcome = try {
                engine.instance(n, variant).outcome()
            } catch (e: io.github.mbackschat.a12.dm.interpreter.UnsupportedConstructException) {
                throw IllegalStateException(
                    "${scenario.name} (N=$n, variant=$variant): refusing to time — ${e.message}", e)
            }
            requireEligible(scenario, n, variant, outcome)
        }
    }

    /**
     * Assert complete interpreter/dynamic-Groovy/generated-Java signatures at every requested scale, with all
     * engines built once and no clocks involved. This is the correctness precondition for a multi-scale curve:
     * each exact document on the curve is proved semantically eligible before the first timed observation.
     */
    fun assertSemanticAgreement(scenario: PerfScenario, scales: List<Int>, withJava: Boolean = true) {
        require(scales.isNotEmpty()) { "at least one semantic-agreement scale is required" }
        require(scales.all { it > 0 }) { "semantic-agreement scales must be positive" }
        scenario.requireComparativeBindings()
        preflight(scenario, scales.max())
        val interpreter = InterpreterPerfEngine.build(scenario)
        val groovy = KernelPerfEngine.build(scenario)
        val java = if (withJava) KernelJavaPerfEngine.build(scenario) else null
        scales.forEach { n ->
            assertInstanceAgreement(
                scenario,
                n,
                interpreter.instance(n),
                groovy.instance(n),
                java?.instance(n),
            )
        }
    }

    /**
     * **Cold first-call latency** (ms): the MEDIAN over [repeats] FRESH attempts — each a fresh [DmInterpreter] +
     * a fresh [Document] at scale [n] + exactly ONE `validateFull`, with **no warmup**. This is the realistic
     * **startup / one-shot / first-call** cost (the lazy index build + first parse + un-JIT-warmed execution)
     * that the warm [measure]/[runScenario] path deliberately excludes — and exactly where the index-build
     * O(N²) hid. Cold/startup is a **co-equal goal** with steady-state, so it gets its own gate (the growth
     * factor across N catches a reintroduced cold O(N²)); the repeated median rejects scheduler outliers without
     * cherry-picking the least-contended attempt.
     */
    fun freshRunMedianMillis(scenario: PerfScenario, n: Int, repeats: Int = 3): Double {
        require(repeats > 0) { "repeats must be positive" }
        preflight(scenario, n)
        // The cost-capped preflight cannot prove a registered callback for data that appears only after its cap.
        // Use a separate exact-N instance so the timed attempts below remain genuine first calls.
        exactEligibleOutcome(scenario, n)
        return median(DoubleArray(repeats) {
            val inst = InterpreterPerfEngine.build(scenario).instance(n)   // fresh engine + fresh doc (outside timing)
            val t = System.nanoTime(); inst.run(); (System.nanoTime() - t) / 1e6
        })
    }

    /**
     * Single-document mode: build all three engines (interp, kernel-Groovy, kernel-Java), materialize the
     * canonical instance (variant 0) for each, assert complete semantic signatures agree THREE-way (correctness
     * first — interp == groovy == java), then time each. The O(N²) gate + the Groovy-vs-Java fairness measure.
     * Pass [withJava] = false to skip the (compile-cost) Java baseline when only the interp/Groovy gate is wanted.
     */
    fun runScenario(scenario: PerfScenario, n: Int, warmup: Int = 12, iters: Int = 8, withJava: Boolean = true): PerfRow {
        scenario.requireComparativeBindings()
        preflight(scenario, n)
        val iInst = InterpreterPerfEngine.build(scenario).instance(n)
        val gInst = KernelPerfEngine.build(scenario).instance(n)
        val jInst = if (withJava) KernelJavaPerfEngine.build(scenario).instance(n) else null
        val iResult = assertInstanceAgreement(scenario, n, iInst, gInst, jInst)
        val identity = scenario.completeIdentity(n, observed = listOf(iResult.size), variants = listOf(0))
        val gStats = measure(warmup, iters) { gInst.run() }
        val jStats = jInst?.let { measure(warmup, iters) { it.run() } }
        val iStats = measure(warmup, iters) { iInst.run() }
        return PerfRow(identity, n, iResult.size, iStats, gStats, jStats)
    }

    private fun assertInstanceAgreement(
        scenario: PerfScenario,
        n: Int,
        interpreter: RunInstance,
        groovy: RunInstance,
        java: RunInstance?,
    ): List<String> {
        val iOutcome = interpreter.outcome()
        requireEligible(scenario, n, 0, iOutcome)
        val groovyResult = groovy.run()
        assertSignaturesAgree(scenario.name, n, "interp", iOutcome.signatures, "groovy", groovyResult)
        java?.run()?.let { javaResult ->
            assertSignaturesAgree(scenario.name, n, "groovy", groovyResult, "java", javaResult)
        }
        return iOutcome.signatures
    }

    /**
     * **Repeated-eval mode (form-input / batch).** Build each engine ONCE, pre-build [variants] slightly-
     * different instances (variant 0..M-1, OUTSIDE timing), assert complete correctness on every variant,
     * then time the LOOP of M evaluations and report **per-eval** stats. This is the reuse pattern the kernel's
     * codegen + per-run caches are tuned for; comparing its ratio to the single-document [runScenario] ratio
     * reveals whether the interpreter's per-call tree-WALK (parsing is one-and-done — cached in `ParsedModel`)
     * costs it against the kernel's compiled rules under reuse.
     */
    fun runRepeated(
        scenario: PerfScenario, n: Int, variants: Int = 20, warmupLoops: Int = 3, timedLoops: Int = 5,
        withJava: Boolean = true,
    ): RepeatedRow {
        scenario.requireComparativeBindings()
        preflightVariants(scenario, n, variants)
        val iEng = InterpreterPerfEngine.build(scenario)
        val gEng = KernelPerfEngine.build(scenario)
        val jEng = if (withJava) KernelJavaPerfEngine.build(scenario) else null
        val iInsts = (0 until variants).map { iEng.instance(n, it) }
        val gInsts = (0 until variants).map { gEng.instance(n, it) }
        val jInsts = jEng?.let { e -> (0 until variants).map { e.instance(n, it) } }
        val cardinalities = IntArray(variants)
        // Every actual variant is tri-checked at full signature granularity before any timed loop.
        for (variant in 0 until variants) {
            val iOutcome = iInsts[variant].outcome()
            requireEligible(scenario, n, variant, iOutcome)
            cardinalities[variant] = iOutcome.signatures.size
            val groovy = gInsts[variant].run()
            assertSignaturesAgree(scenario.name, n, "interp", iOutcome.signatures, "groovy", groovy)
            jInsts?.get(variant)?.run()?.let { java ->
                assertSignaturesAgree(scenario.name, n, "groovy", groovy, "java", java)
            }
        }
        val identity = scenario.completeIdentity(
            n,
            observed = cardinalities.toList(),
            variants = (0 until variants).toList(),
        )
        val gStats = measureLoop(warmupLoops, timedLoops, gInsts)
        val jStats = jInsts?.let { measureLoop(warmupLoops, timedLoops, it) }
        val iStats = measureLoop(warmupLoops, timedLoops, iInsts)
        return RepeatedRow(identity, n, variants, iStats, gStats, jStats)
    }

    /**
     * **The IF137-ratified paired growth estimator** — interleaved N/4N samples, the **median of the within-pair
     * ratios**. Each of [pairs] pairs measures [small] then [large] back-to-back (interleaved, so both share the
     * same machine-load slice), and the within-pair ratio is `large/small`; the estimator is the **median** of
     * those ratios. Median (not the old min-of-repeats) is the robust central value the contract requires: a min
     * understates under a single clean slice (the min hack "flipped pass↔fail on identical code"
     * INTERPRETER-PERFORMANCE.md) and a mean is dragged by one contended slice. [measureAt] returns either one
     * warmed observation or a repeated median at that scale—never an inner minimum. A linear axis medians ≈4×,
     * a quadratic one ≈16×; the trip line is
     * [PerfBounds.SUB_QUADRATIC_MAX] (the ratified backstop — a tighter bar is an owner-gated per-number promotion,
     * IF137). Reported [Growth.smallValue]/[Growth.largeValue] are the medians across pairs, for the log line.
     */
    fun pairedGrowth(small: Int, large: Int, pairs: Int = PerfBounds.GROWTH_PAIRS, measureAt: (Int) -> Double): Growth {
        require(small > 0) { "small scale must be positive" }
        require(large > small) { "large scale must be greater than small scale" }
        require(pairs > 0 && pairs % 2 == 1) { "pairs must be a positive odd number" }
        val ratios = DoubleArray(pairs)
        val smalls = DoubleArray(pairs)
        val larges = DoubleArray(pairs)
        for (i in 0 until pairs) {
            val s = checkedGrowthObservation(measureAt(small), small, i)
            val l = checkedGrowthObservation(measureAt(large), large, i)
            smalls[i] = s; larges[i] = l; ratios[i] = l / s
        }
        return Growth(median(ratios), median(smalls), median(larges), smallN = small, largeN = large)
    }

    /**
     * **A routine-tier growth lane: assert the floor AND record both endpoints against this runner class's anchor.**
     *
     * This is the seam every `@Tag("perf")` growth bar goes through, and it exists because seven of the nine
     * routine-tier classes recorded *nothing*: the record-and-arm step lived in one class's private helper, so
     * every other lane could measure, pass, and leave no trace. `perf-history.sh`, the change-point detection, the
     * cross-engine margin band and the absolute anchor are all built on the record, which made those lanes
     * invisible to every trend instrument the project has (IF296).
     *
     * **Why both halves are needed, and neither substitutes for the other.** A growth ratio is blind to a UNIFORM
     * slowdown — it is unchanged at every constant factor — so a 2x-slower engine passes the floor forever. The
     * absolute anchor catches exactly that, and only within one runner class, which is why it is keyed by
     * [PerfEnvironment] and asserted only in the isolated single-fork lane.
     *
     * [identityAt] must produce a label that changes when the measured WORK changes (a scenario fingerprint, not a
     * prose name): the label is part of the anchor key, so a scenario whose content moves drops its anchor rather
     * than silently comparing new work against an old number.
     */
    fun assertSubQuadraticLane(
        label: String,
        small: Int,
        identityAt: (Int) -> String,
        culprit: String? = null,
        large: Int = small * 4,
        clock: String = "cpu",
        engine: String = "interpreter",
        floor: Double = PerfBounds.SUB_QUADRATIC_MAX,
        unit: String? = null,
        batch: Int = 1,
        measureAt: (Int) -> Double,
    ): Growth {
        PerfLaneGate.requireMeasuringLane()
        require(batch >= 1) { "$label: batch must be at least 1" }
        // [batch] DECLARES how many calls the caller repeats inside its own timed block, and the only thing this
        // seam does with it is fold it into the KEY. It cannot do the repeating: [measureAt] hands back an
        // already-timed median, so the loop has to live inside the caller's timed region. What the declaration buys
        // is that the key can no longer drift from the batch — a batch changes what one observation MEANS, and the
        // first batched lane was re-keyed by hand while the second was not, whose next gate run then failed against
        // its own stale anchor. Sampling parameters stay out of the key by the same rule: more warmups or
        // iterations estimate the SAME quantity, a bigger batch is a different one.
        val identityForKey: (Int) -> String =
            if (batch == 1) identityAt else { n -> rebatchedLabel(identityAt(n), batch) }
        val accepted = assertGrowthBelowCatastropheFloor(label, culprit, floor) {
            pairedGrowth(small, large, measureAt = measureAt).also { g ->
                if (unit != null) {
                    println(
                        "[perf] %s → %s %s: %.1fms@%d → %.1fms@%d %s  median-growth=%.2f× (linear≈4, quadratic≈16)"
                            .format(Locale.ROOT, identityAt(small), identityAt(large), label,
                                g.smallValue, small, g.largeValue, large, unit, g.ratio),
                    )
                }
            }
        }
        anchorBothEndpoints(label, identityForKey, clock, engine, accepted, small, large, measureAt)
        return accepted
    }

    /**
     * Anchor a lane's two endpoints, recording **both** before letting either one fail.
     *
     * The order matters and is not cosmetic. `assertWithinBudget` records an unanchored observation and then
     * rethrows, so anchoring endpoint-by-endpoint means a lane whose small scale is unanchored never records its
     * large one — and `perf-anchor.sh` can only author keys the record contains. Arming a new lane then costs TWO
     * full gate runs, a class with two lanes four, and the 13 lanes added on 2026-08-13 would have needed roughly
     * half an hour of 4-minute gates to arm. Collecting the failure and rethrowing after both have recorded makes
     * one run enough, and loses nothing: the same exception still fails the same test.
     */
    private fun anchorBothEndpoints(
        label: String,
        identityAt: (Int) -> String,
        clock: String,
        engine: String,
        accepted: Growth,
        small: Int,
        large: Int,
        measureAt: (Int) -> Double,
    ) {
        var failure: Throwable? = null
        for ((scale, observed) in listOf(small to accepted.smallValue, large to accepted.largeValue)) {
            try {
                PerfAnchors.assertWithinBudget(
                    ObservationKey(
                        lane = label,
                        identityLabel = identityAt(scale),
                        n = scale,
                        engine = engine,
                        clock = clock,
                    ),
                    observed,
                ) { medianOf(PerfBounds.GROWTH_PAIRS) { measureAt(scale) } }
            } catch (unanchored: UnanchoredObservationException) {
                if (failure == null) failure = unanchored else failure.addSuppressed(unanchored)
            }
        }
        failure?.let { throw it }
    }

    /**
     * Assert a growth estimate stays under a CATASTROPHE floor, **re-measuring before it fails**.
     *
     * The low-level primitive. A routine-tier lane calls [assertSubQuadraticLane] instead, which adds the
     * record-and-arm step; this one stays public for the harness's own unit tests (which script their ratios and
     * must not record) and for the checkpoint tier.
     *
     * The floor exists to catch an algorithmic blowup: linear medians ≈4x, quadratic ≈16x, and the trip line
     * sits at [PerfBounds.SUB_QUADRATIC_MAX] between them. A contended CI slice inflates one estimate by a few
     * percent and does not reproduce; a blowup is a property of the code and does.
     *
     * **The acceptance rule is the MEDIAN of the re-measurements, not "any attempt below the floor".** That
     * distinction is the whole correctness of this helper: retrying until one observation passes would make a
     * real breach *more* likely to be hidden the more often it is retried — `10x, 10x, 8x` would be admitted.
     * With the median, that sequence fails and `9.5x, 4x, 4x` passes, which is the intended asymmetry. Note
     * what is and is not claimed: this **reduces** the false-GREEN probability relative to retry-until-pass and
     * leaves the single-estimate happy path exactly as strict as the guard was before any retry existed, but a
     * timing floor is a statistical instrument and no arrangement of it can promise zero false GREENs.
     *
     * The happy path costs exactly one estimate; only a breach pays for the re-measurements, and the failure
     * names every attempt so a real regression is unmistakable. This is not a licence to run perf assertions
     * under load — the isolation rule in CLAUDE.md stands.
     */
    fun assertGrowthBelowCatastropheFloor(
        label: String,
        /** What most likely regressed, appended to the failure so a real breach still points somewhere. */
        culprit: String? = null,
        floor: Double = PerfBounds.SUB_QUADRATIC_MAX,
        attempts: Int = GROWTH_RETRY_ATTEMPTS,
        /** The unit [Growth.smallValue]/[Growth.largeValue] are in, for the breach text only. Defaults to the
         *  timing lanes' millisecond; a byte lane passes its own, or a breach reads a byte count as a duration. */
        unit: String = "ms",
        /** Where this is running, which decides what a breach MEANS. Injected so the three outcomes are testable
         *  without a hosted runner; production callers take the ambient decision. */
        enforcement: TimingEnforcement = TimingEnforcement.forGrowthFloor(),
        estimate: () -> Growth,
    ): Growth {
        val seen = acceptOnMedianOfRetries(
            attempts = attempts,
            breaches = { it.ratio >= floor },
            rank = { it.ratio },
            sample = estimate,
        )
        // The ACCEPTED estimate is returned so a caller can anchor its absolute medians without paying to
        // re-measure them (PerfAnchors) — the same numbers acceptance was decided on, never a fresh sample.
        val accepted = seen.accepted
        if (accepted.ratio < floor) return accepted
        val breach =
            "$label median within-pair N/4N growth stayed at or above the ${floor}x catastrophe floor across " +
                "${seen.taken.size} independent re-measurements: " +
                seen.taken.joinToString(", ") {
                    "${it.ratio} (${it.smallValue}$unit -> ${it.largeValue}$unit)"
                } +
                ". Acceptance is the median of these, so one low outlier cannot clear a real breach. Load " +
                "inflates one estimate and does not reproduce; an algorithmic blowup does (linear medians " +
                "~4x, quadratic ~16x)." + culprit?.let { " Likely cause: $it." }.orEmpty()
        // WHERE the breach happened decides what it means — this floor is self-referential, so it is a property of
        // the machine as much as of the code (TimingEnforcement). The cross-engine bars are untouched by this and
        // gate everywhere, which is what keeps "always faster than the kernel, including scaling" enforced.
        return when (enforcement) {
            TimingEnforcement.GATED -> throw AssertionError(breach)
            TimingEnforcement.FLAGGED -> accepted.also {
                println("[perf][FLAG] $breach  (reported, not failed: shared hosted hardware — see TimingEnforcement)")
            }
            TimingEnforcement.UNJUDGED -> accepted.also {
                println("[perf][note] growth not judged outside the isolated single-fork lane: $breach")
            }
        }
    }

    /** One cross-engine complexity-class estimate: the interp/kernel ratio at two scales, and its growth. */
    data class RatioGrowth(
        val iSmall: Double, val kSmall: Double, val iLarge: Double, val kLarge: Double,
    ) {
        val ratioSmall: Double get() = iSmall / kSmall
        val ratioLarge: Double get() = iLarge / kLarge
        /** How much WORSE the interpreter got against the kernel between the two scales. */
        val growth: Double get() = ratioLarge / ratioSmall
    }

    /**
     * **A cross-engine complexity-class bar, with the backstop its growth-lane siblings already have.**
     * The claim is that the interpreter does not worsen RELATIVE to the kernel as N grows — a ratio of ratios,
     * so four measurements multiply into one number.
     *
     * <p><b>Why this needed extracting.</b> Two sites asserted exactly this shape by hand
     * ({@code GroupScopedFillPerfTest}, {@code OuterCorrelationPerfTest}) and neither had ANY of the
     * protections the single-engine growth lanes get: no interleaving, no re-measurement of a breach, fixed
     * warmups as low as two runs per arm. That is the harder measurement getting the weaker instrument, and it
     * is backwards for a reason this file already documents: a growth factor divides ONE engine's two scales, so
     * a cold JVM inflates both and largely cancels, while a parity row divides two DIFFERENT engines that warm
     * at different rates and nothing cancels. Measured consequence: across four hosted runs of BYTE-IDENTICAL
     * measured code the group-scope bar went fail, pass, fail, pass — while the interpreter stayed 4x-12x faster
     * than the kernel at both scales in every one of them, including both failures.
     *
     * <p><b>What this does and does not change.</b> The ceiling is untouched, and so is WHERE it applies: this
     * family gates on every machine, hosted included. Only the re-measurement is new. A breach is re-measured and must
     * survive the MEDIAN of [attempts] independent estimates, which is the same contract
     * [assertGrowthBelowCatastropheFloor] applies and the one IF281/IF282 ratified — so a single contended slice
     * can no longer fail the build, and a real relative blowup still does. [estimate] must measure all four
     * numbers in ONE pass so they share a load slice; that is the caller's half and cannot be done here.
     */
    fun assertCrossEngineClassHolds(
        label: String,
        ceiling: Double,
        attempts: Int = GROWTH_RETRY_ATTEMPTS,
        estimate: () -> RatioGrowth,
    ): RatioGrowth {
        // This builds a BAR, so it asks the lane gate like the other two bar-building seams. It is shaped like
        // the ungated [assertGrowthBelowCatastropheFloor] — an estimate arrives, nothing is measured here — but
        // the resemblance is misleading: that rule is only ever reached THROUGH the gated
        // [assertSubQuadraticLane], while this one is called DIRECTLY by perf classes, so nothing else is in the
        // path. Both current callers carry a lane tag, which is precisely why the hole was invisible.
        PerfLaneGate.requireMeasuringLane()
        val seen = acceptOnMedianOfRetries(
            attempts = attempts,
            breaches = { it.growth >= ceiling },
            rank = { it.growth },
            sample = estimate,
        )
        val accepted = seen.accepted
        if (accepted.growth < ceiling) return accepted
        val breach = "$label interp/kernel ratio grew ${accepted.ratioSmall}x -> ${accepted.ratioLarge}x " +
            "(${accepted.growth}x, ceiling ${ceiling}x) across ${seen.taken.size} independent re-measurements: " +
            seen.taken.joinToString(", ") { "${it.growth}x" } +
            ". Acceptance is their median, so one contended slice cannot fail it — this is a RELATIVE claim, so " +
            "note the interpreter may still be far faster than the kernel at both scales while its ADVANTAGE " +
            "narrows, which is what this bar is about."
        // GATES EVERYWHERE — deliberately NOT routed through TimingEnforcement, unlike the growth floor.
        // A cross-engine ratio is dimensionless: both engines run in the same JVM, on the same vCPUs, in the same
        // load slice, so contention moves both arms and largely cancels — which is exactly why this family is
        // excluded from the shared/isolated distinction (TimingEnforcement's own doc, adapter/build.gradle:469,
        // INTERPRETER-PERFORMANCE.md, TESTING-SPEC.md all say so). The first version of this helper defaulted to
        // `TimingEnforcement.forGrowthFloor()`, which returns FLAGGED whenever `CI` is set — silently turning a
        // hosted breach from a failure into a println on both call sites, while the doc above claimed the ceiling
        // was untouched. A cold review caught it. Switching a guard off is exactly what INTERPRETER-PERFORMANCE.md
        // warns about: "Collapsing those two into one rule switches guards off, and that is not hypothetical."
        throw AssertionError(breach)
    }

    /**
     * **A REUSE ceiling: the stateful call must stay cheaper than the stateless one that answers identically.**
     *
     * The quantity is what holding an analysis is FOR, so the ceiling a caller passes comes from the feature's
     * purpose and never from a figure this lane produced. IF137's promotion gate exists because "a budget fitted
     * to an already-measured implementation gates nothing", and a reuse ratio is the easiest place in the estate
     * to fit one — the measurement is sitting right beside the constant. The derivation therefore belongs with the
     * number's ratification, not here; this seam only enforces it.
     *
     * **It gates everywhere, deliberately, and for [assertCrossEngineClassHolds]'s reason rather than the growth
     * floor's.** Both arms run in ONE JVM, interleaved per pair on the same vCPUs in the same load slice, so
     * contention moves both and largely cancels. That makes the ratio dimensionless: there is no machine on which
     * holding an analysis stops being cheaper than not holding one, so a breach is a property of the code and
     * routing it through [TimingEnforcement] would switch the bar off exactly where it still means something.
     *
     * A breach must survive the MEDIAN of [attempts] independent estimates — the acceptance rule
     * [acceptOnMedianOfRetries] owns and IF281/IF282 ratified. The happy path costs one estimate.
     */
    fun assertReuseRatioBelowCeiling(
        label: String,
        ceiling: Double,
        attempts: Int = GROWTH_RETRY_ATTEMPTS,
        estimate: () -> Reuse,
    ): Reuse {
        // This builds a BAR, so it asks the lane gate — PerfLaneGate's own doc names the three seams that do and
        // says to add a fourth here when one appears, because "it looks like the inner rule" is the specific
        // reasoning that once left a seam ungated for two days.
        PerfLaneGate.requireMeasuringLane()
        val outcome = acceptOnMedianOfRetries(
            attempts = attempts,
            // Strict, and the strictness is the bar: a ratio of exactly 1.0 is a reuse that saves NOTHING, which
            // is the case the ceiling exists to refuse (`ReuseCeilingRetryTest`).
            breaches = { it.ratio >= ceiling },
            rank = { it.ratio },
            sample = estimate,
        )
        if (outcome.accepted.ratio < ceiling) return outcome.accepted
        throw AssertionError(
            "$label reuse ratio stayed at or above the ${ceiling}x ceiling across ${outcome.taken.size} " +
                "independent re-measurements: " +
                outcome.taken.joinToString(", ") {
                    "${it.ratio} (stateless ${it.statelessMs}ms -> stateful ${it.statefulMs}ms)"
                } +
                ". Acceptance is their median, so one contended slice cannot fail it. What this says is that the " +
                "held analysis stopped being cheaper than not holding one, which is the only thing holding it is " +
                "for — so the reuse is either no longer happening or no longer worth its bookkeeping.",
        )
    }

    /**
     * Run [instance] until its per-run cost stops improving, and report how many runs that took.
     *
     * **Why a cross-engine comparison needs this and a single engine's growth factor does not.** A growth factor
     * divides one engine's two scales, so a cold JVM inflates both and largely cancels. A parity row divides two
     * DIFFERENT engines, which warm at different rates: traced per pair on `CO-correlation`, the interpreter at
     * N=200 was 1.88x-2.34x slower in the first pair than the third while generated Java was 1.75x-1.80x and the
     * Groovy validator at N=800 was 1.51x-1.70x — all eighteen series, in all three runs. Nothing cancels there;
     * the ratio simply reports where on each engine's warm curve the sample landed.
     *
     * Adaptive rather than a bigger constant [measure] warmup, for two reasons. The scenarios span 0.2 ms to
     * 100 ms per evaluation, so no single count is warm for the cheap ones without being wasteful for the dear
     * ones; and a per-observation warmup is paid again by every pair, while this is paid once per instance.
     */
    internal fun warmToSteadyState(instance: RunInstance, budget: WarmUpBudget = WarmUpBudget()): WarmOutcome {
        val warm = SteadyStateWarmUp(budget)
        do {
            val batch = DoubleArray(budget.batch) {
                val started = System.nanoTime()
                instance.run()
                (System.nanoTime() - started) / 1e6
            }
        } while (!warm.hasSettled(median(batch)))
        return WarmOutcome(warm.runs, checkNotNull(warm.stop))
    }

    /** What one instance's warm cost, and whether it reached steady state or ran out of budget. */
    internal data class WarmOutcome(val runs: Int, val stop: WarmStop) {
        override fun toString(): String = "$runs${if (stop == WarmStop.FLATTENED) "" else "!${stop.label}"}"
    }

    /** Independent re-measurements a catastrophe floor demands before it calls a breach real. */
    const val GROWTH_RETRY_ATTEMPTS = 3

    /** Every attempt taken, and the one acceptance was decided on. */
    internal data class RetryOutcome<T>(val accepted: T, val taken: List<T>)

    /**
     * Take one sample; while it breaches, take more up to [attempts]; accept the **median** by [rank].
     *
     * **Every bar in this file now reaches its acceptance decision through here** — the growth floor, the
     * cross-engine class and the reuse ceiling — so the rule has exactly one implementation and one place where
     * it can be got wrong. It was extracted from [assertGrowthBelowCatastropheFloor] for the comparative bars to
     * share, and the two older seams kept private copies of it until 2026-09-17; a third copy arriving with the
     * reuse ceiling is what made consolidating cheaper than maintaining.
     *
     * The median is the whole correctness of it: returning as soon as any attempt passes makes a real breach
     * *more* likely to be admitted the more often it is retried, which is the shape an external review falsified
     * once already. The happy path costs exactly one sample; only a breach pays.
     */
    internal fun <T> acceptOnMedianOfRetries(
        attempts: Int = GROWTH_RETRY_ATTEMPTS,
        breaches: (T) -> Boolean,
        rank: (T) -> Double,
        sample: () -> T,
    ): RetryOutcome<T> {
        require(attempts > 0 && attempts % 2 == 1) { "attempts must be a positive odd number, was $attempts" }
        val taken = mutableListOf(sample())
        // Once the first sample breaches, take the FULL count — never "stop as soon as one passes". Two reasons,
        // and the first one bites immediately: an even number of attempts has no median, and `sorted[size / 2]`
        // then silently returns the UPPER of the two, which is the opposite of the value that was intended. The
        // second is that stopping on the first passing sample is optional stopping, which is on this project's
        // own refuted list (IF281) — it inflates exactly the error budget the retry is supposed to control.
        if (breaches(taken.single())) {
            while (taken.size < attempts) {
                taken += sample()
            }
        }
        return RetryOutcome(taken.sortedBy(rank)[taken.size / 2], taken)
    }

    /**
     * Apply the paired estimator to interpreter, dynamic Groovy, and generated Java in one reusable-engine run.
     * Both exact instances are tri-checked before timing, then **three** independent bars must hold:
     *
     * 1. the interpreter beats both strategies at N and at 4N ([requireInterpreterBeatsBoth]) — the absolute bar;
     * 2. the interpreter's **per-unit cost** is the lowest of the three ([PairedAcceptance]) — the cross-engine
     *    scaling bar. It replaced a comparison of raw N/4N growth ratios, which ranks engines by fixed-cost share
     *    and so is failed deterministically by the leanest one (IF279, mechanism established in IF205);
     * 3. the interpreter's **own** N/4N growth stays under [PerfBounds.SUB_QUADRATIC_MAX], measured on the CPU
     *    clock and re-measured before failing ([assertGrowthBelowCatastropheFloor]) — the catastrophe bar, which
     *    is self-referential because (2) leaves ~10× of slope headroom over the kernel and cannot see a blowup.
     *
     * The kernel strategies' own growth ratios stay in the returned [PairedPerfCurve] for the log line; they are
     * reported, not gated.
     */
    fun pairedScenarioGrowth(
        scenario: PerfScenario,
        small: Int,
        large: Int,
        pairs: Int = PerfBounds.GROWTH_PAIRS,
        warmup: Int = 12,
        iters: Int = 8,
        /**
         * The lane a caller records its observations under, or `null` to record nothing about this run's warm.
         *
         * Passed in rather than fixed here because this helper serves three callers with three lanes, and the warm
         * outcome's only value is being **joinable** to the medians it qualifies — a label invented here would
         * attach it to no observation at all. `null` is the honest answer for a caller that records no rows.
         */
        lane: String? = null,
    ): PairedPerfCurve {
        val started = System.nanoTime()
        require(small > 0) { "small scale must be positive" }
        require(large > small) { "large scale must be greater than small scale" }
        require(pairs > 0 && pairs % 2 == 1) { "pairs must be a positive odd number" }
        require(warmup >= 0) { "warmup must not be negative" }
        require(iters > 0) { "iters must be positive" }
        scenario.requireComparativeBindings()
        preflight(scenario, large)

        val interpreter = InterpreterPerfEngine.build(scenario)
        val groovy = KernelPerfEngine.build(scenario)
        val java = KernelJavaPerfEngine.build(scenario)
        val iSmall = interpreter.instance(small)
        val gSmall = groovy.instance(small)
        val jSmall = java.instance(small)
        val iLarge = interpreter.instance(large)
        val gLarge = groovy.instance(large)
        val jLarge = java.instance(large)
        val smallResult = assertInstanceAgreement(scenario, small, iSmall, gSmall, jSmall)
        val largeResult = assertInstanceAgreement(scenario, large, iLarge, gLarge, jLarge)
        // ONE identity per scale, shared by the warm rows and the PerfRows below. Two copies of the same
        // expression would let a later edit drift the label or fingerprint apart, which is exactly what the
        // record's join check would then reject.
        val smallIdentity = scenario.completeIdentity(small, listOf(smallResult.size), listOf(0))
        val largeIdentity = scenario.completeIdentity(large, listOf(largeResult.size), listOf(0))

        // Every instance is warmed towards steady state ONCE, before the first timed pair. Without this the first
        // pair is measured on six differently-cold engines and the ratio reports their warm curves rather than
        // their costs — see warmToSteadyState for the traced evidence. Paid per instance, not per pair, and
        // outside the retry loop below, so a re-measurement never re-warms.
        //
        // "Towards", not "to": a budget cap can end a warm before the curve flattens, and the engine most likely
        // to hit one is the dearest — exactly the engine whose coldness distorts a ratio most. That outcome is
        // therefore REPORTED rather than assumed away, since a silent cap would restore the state IF282 is about.
        //
        // LARGE first, deliberately. JIT compilation is per method and both scales run the same methods, so the
        // large instance warms them with four times fewer evaluations — and the small instance then settles in
        // the two probes its own rule needs rather than warming the shared code from cold a second time.
        // The engine names are the OBSERVATION's, not shorthand: a warm outcome's only value is that it joins to
        // the median it qualifies, and "interp" against a recorded "interpreter" is a row that qualifies nothing.
        val warmed = listOf(
            Triple("groovy", large, gLarge), Triple("java", large, jLarge), Triple("interpreter", large, iLarge),
            Triple("groovy", small, gSmall), Triple("java", small, jSmall), Triple("interpreter", small, iSmall),
        ).map { (engine, n, instance) -> Triple(engine, n, warmToSteadyState(instance)) }
        // RECORDED, not just printed. The outcome qualifies every ratio measured on that instance, so it belongs
        // beside the medians in the committed record — otherwise the cap's justification lives in a build/ log
        // that clean deletes, a returning cap stop fails nothing, and no later run can compare against it (IG121).
        if (lane != null) {
            val identityFor = mapOf(small to smallIdentity, large to largeIdentity)
            for ((engine, n, outcome) in warmed) {
                val identity = identityFor.getValue(n)
                PerfObservations.recordWarm(
                    lane = lane,
                    scenario = "${identity.label} fingerprint=${identity.fingerprint}",
                    n = n,
                    engine = engine,
                    runs = outcome.runs,
                    stop = outcome.stop.label,
                )
            }
        }
        val warmRuns = warmed.map { (engine, n, outcome) -> "$engine@$n=$outcome" }
        val capped = warmed.filter { (_, _, outcome) -> outcome.stop != WarmStop.FLATTENED }
        if (capped.isNotEmpty()) {
            progress("[perf][note] ${scenario.name}: " + capped.joinToString(", ") { (engine, n, outcome) ->
                "$engine@$n ran out of warm budget after ${outcome.runs} runs (${outcome.stop.label})"
            } + " — its timed observations may still be on a falling curve")
        }

        fun sample(): ParitySample {
            val s = ParitySample(pairs)
            for (pair in 0 until pairs) {
                s.groovySmall[pair] = measure(warmup, iters) { gSmall.run() }.medianMs
                s.javaSmall[pair] = measure(warmup, iters) { jSmall.run() }.medianMs
                s.interpSmall[pair] = measure(warmup, iters) { iSmall.run() }.medianMs
                s.groovyLarge[pair] = measure(warmup, iters) { gLarge.run() }.medianMs
                s.javaLarge[pair] = measure(warmup, iters) { jLarge.run() }.medianMs
                s.interpLarge[pair] = measure(warmup, iters) { iLarge.run() }.medianMs
            }
            return s
        }

        // A COMPARATIVE breach is re-measured before it fails, on the same rule the growth floor has used since
        // 2026-08-08: take independent samples and accept the MEDIAN of them, never the best.
        //
        // This is not caution for its own sake. The tightest row in the estate is the compute-overlay
        // $-correlation path, whose margin is 1.99x against generated Java at N=200 while its OWN spread across
        // three sequential isolated runs is 1.036x (re-measured 2026-08-12). The margin is the larger of the two only since the steady-state
        // warm above; before it the same row spread 1.90x and came within 1.35x of a loss, so a single contended
        // sample could red the nightly — precisely the failure mode the whole enforcement split exists to remove.
        // Retrying until one attempt passes would be worse than nothing (it hides a real loss the more often it is
        // retried); the median keeps the asymmetry honest, and the happy path pays for exactly one sample.
        val outcome = acceptOnMedianOfRetries(
            breaches = { it.worstInterpreterRatio() >= 1.0 },
            rank = ParitySample::worstInterpreterRatio,
            sample = ::sample,
        )
        val accepted = outcome.accepted
        val iSmallMs = accepted.interpSmall
        val gSmallMs = accepted.groovySmall
        val jSmallMs = accepted.javaSmall
        val iLargeMs = accepted.interpLarge
        val gLargeMs = accepted.groovyLarge
        val jLargeMs = accepted.javaLarge

        val smallRow = PerfRow(
            smallIdentity,
            small,
            smallResult.size,
            stats(iSmallMs.copyOf()),
            stats(gSmallMs.copyOf()),
            stats(jSmallMs.copyOf()),
        )
        val largeRow = PerfRow(
            largeIdentity,
            large,
            largeResult.size,
            stats(iLargeMs.copyOf()),
            stats(gLargeMs.copyOf()),
            stats(jLargeMs.copyOf()),
        )
        val iGrowth = growth(iSmallMs, iLargeMs, small, large)
        val gGrowth = growth(gSmallMs, gLargeMs, small, large)
        val jGrowth = growth(jSmallMs, jLargeMs, small, large)
        if (outcome.taken.size > 1) {
            println("[perf] ${scenario.name}: comparative breach re-measured ${outcome.taken.size}x, accepted the " +
                "median of " + outcome.taken.joinToString(", ") { "%.2f".format(Locale.ROOT, it.worstInterpreterRatio()) })
        }
        if (TRACE_PER_PAIR) {
            progress("[perf][trace] ${scenario.name} steady-state warm runs: ${warmRuns.joinToString(" ")}")
            outcome.taken.forEachIndexed { attempt, taken ->
                taken.trace("${scenario.name} attempt=$attempt", small, large).forEach(::progress)
            }
        }
        // In a FINALLY, because the four assertions below throw on a breach — and a breaching lane is the one whose
        // cost is most worth having. Written after them it was silently absent from exactly that run, which would
        // make the tier total a sum over the lanes that happened to pass (IG127, found by a cold review).
        try {
            requireInterpreterBeatsBoth(scenario, smallRow)
            requireInterpreterBeatsBoth(scenario, largeRow)
            PairedAcceptance.requireInterpreterHasLowestPerUnitCost(
                scenario.name,
                ScaleStep(small, large),
                EngineCurve("interpreter", iGrowth),
                listOf(EngineCurve("Groovy", gGrowth), EngineCurve("Java", jGrowth)),
            )
            val byScale = mapOf(small to iSmall, large to iLarge)
            assertGrowthBelowCatastropheFloor(
                "${scenario.name} interpreter",
                culprit = "a rescan or per-row rebuild on this scenario's shape",
            ) {
                pairedGrowth(small, large, pairs) { n ->
                    measureCurrentThreadCpu(warmup, iters) { byScale.getValue(n).run() }.medianMs
                }
            }
        } finally {
            // Covers everything this lane spent: agreement, six warms, the timed pairs, any re-measurement a breach
            // forced, and the catastrophe-floor re-measurement above. The tier's wall time is the sum of these.
            if (lane != null) {
                PerfObservations.recordCost(lane, scenario.name, (System.nanoTime() - started) / 1e6)
            }
        }
        return PairedPerfCurve(smallRow, largeRow, iGrowth, gGrowth, jGrowth)
    }

    /**
     * One independent measurement of all three engines at both scales, so a comparative breach can be re-measured
     * as a whole. Every array is `pairs` long; index `i` is the i-th interleaved pair, which is what keeps the
     * engines' samples comparable within a pair.
     */
    internal class ParitySample(pairs: Int) {
        val interpSmall = DoubleArray(pairs)
        val groovySmall = DoubleArray(pairs)
        val javaSmall = DoubleArray(pairs)
        val interpLarge = DoubleArray(pairs)
        val groovyLarge = DoubleArray(pairs)
        val javaLarge = DoubleArray(pairs)

        /**
         * The worst `interp/kernel` median ratio over both scales and both strategies — one number that decides
         * whether this sample breaches, so re-measurement can be ordered by it exactly as the growth floor orders
         * its attempts by their growth ratio. `>= 1.0` is a loss to at least one strategy somewhere.
         */
        fun worstInterpreterRatio(): Double = maxOf(
            median(interpSmall.copyOf()) / median(groovySmall.copyOf()),
            median(interpSmall.copyOf()) / median(javaSmall.copyOf()),
            median(interpLarge.copyOf()) / median(groovyLarge.copyOf()),
            median(interpLarge.copyOf()) / median(javaLarge.copyOf()),
        )

        /**
         * Every individual observation, one line per pair and scale — what the medians above throw away.
         *
         * Two very different mechanisms leave the SAME spread in a reduced row and are told apart only here: a
         * **cold first pair** (the interpreter's per-row methods still below the C2 threshold when the first
         * observation is taken) drifts monotonically down the pair index, while **machine contention** does not
         * order itself that way. Enabled by `-Dperf.trace=true`; it asserts nothing and costs nothing when off.
         */
        fun trace(label: String, small: Int, large: Int): List<String> = interpSmall.indices.flatMap { pair ->
            listOf(
                traceLine(label, pair, small, interpSmall[pair], groovySmall[pair], javaSmall[pair]),
                traceLine(label, pair, large, interpLarge[pair], groovyLarge[pair], javaLarge[pair]),
            )
        }

        // A per-pair ratio series was built here as a within-run stand-in for the row's run-to-run noise, so an
        // early-warning band could be derived per row (IG121). It is measurably NOT one and was removed rather
        // than kept unused: a pair ratio divides two three-iteration medians while the reported row divides two
        // nine-sample medians, so it is dispersed by construction — `FVNU-uniqueness @250` spread 3.21x between
        // its pairs in one run against a run-to-run spread near 1.3x. The record's own series is the quantity.

        /** [Locale.ROOT]: a trace is aggregated with `sort -n`/`awk`, which read `12,45` as `12`. */
        private fun traceLine(label: String, pair: Int, n: Int, i: Double, g: Double, j: Double): String =
            "[perf][trace] %s pair=%d N=%d interp=%.2fms groovy=%.2fms java=%.2fms i/g=%.3f i/j=%.3f"
                .format(Locale.ROOT, label, pair, n, i, g, j, i / g, i / j)
    }

    private fun growth(smalls: DoubleArray, larges: DoubleArray, smallScale: Int, largeScale: Int): Growth {
        val ratios = DoubleArray(smalls.size) { index ->
            val small = checkedGrowthObservation(smalls[index], smallScale, index)
            val large = checkedGrowthObservation(larges[index], largeScale, index)
            large / small
        }
        return Growth(median(ratios), median(smalls), median(larges))
    }

    private fun requireInterpreterBeatsBoth(scenario: PerfScenario, row: PerfRow) {
        val groovy = checkNotNull(row.ratioGroovy)
        val java = checkNotNull(row.ratioJava)
        check(groovy < 1.0 && java < 1.0) {
            "${scenario.name}: interpreter must beat both kernel strategies at N=${row.n}; " +
                "ratioGroovy=${"%.2f".format(Locale.ROOT, groovy)}, ratioJava=${"%.2f".format(Locale.ROOT, java)}"
        }
    }

    private fun checkedGrowthObservation(value: Double, scale: Int, pair: Int): Double {
        require(value.isFinite() && value > 0.0) {
            "growth observation must be finite and positive: scale=$scale pair=$pair value=$value"
        }
        return value
    }

    /** The middle value of a sorted copy (does not mutate the caller's array). */
    private fun median(a: DoubleArray): Double = a.sortedArray().let { it[it.size / 2] }

    /**
     * The median of [samples] independent measurements — the estimator [pairedGrowth] applies at one scale, exposed
     * so a re-measurement can reproduce it. Mixing a median-of-N observation with single-sample retries would take a
     * median over samples of different variance, which is not the acceptance rule such a retry claims to apply.
     */
    fun medianOf(samples: Int, measure: () -> Double): Double {
        require(samples > 0 && samples % 2 == 1) { "samples must be a positive odd number, was $samples" }
        return median(DoubleArray(samples) { measure() })
    }

    /** Time the INTERPRETER only at scale [n] (profile/scaling use — no kernel, so high N stays cheap). */
    fun timeInterpreter(scenario: PerfScenario, n: Int, warmup: Int, iters: Int, batch: Int = 1): Stats {
        val inst = preparedInterpreterInstance(scenario, n)
        return measure(warmup, iters) { repeat(batch) { inst.run() } }
    }

    /**
     * The same work, measured as CURRENT-THREAD CPU TIME, for the sub-quadratic growth gates.
     *
     * A growth floor asserts how the algorithm's own work scales, not how long a user waits, so time in which
     * a sibling Gradle test fork preempts this JVM is noise that lands straight in the ratio. It has tripped
     * gates twice: `CharsetPerfTest` in 2026-08-03 (fixed by moving to this clock) and the multi-star gate on
     * hosted runners, where the SAME commit passed one night and failed the next two. Cross-engine throughput
     * and user-facing latency stay on [measure] — [timeInterpreter] — by the same rule.
     */
    /**
     * [batch] evaluations per timed block, so a lane whose single run lands in the noisy sub-10 ms band can lift
     * its median out of it. Declare the same value to [assertSubQuadraticLane], which folds it into the key —
     * a batch changes what one observation means.
     */
    fun timeInterpreterCpu(scenario: PerfScenario, n: Int, warmup: Int, iters: Int, batch: Int = 1): Stats {
        val inst = preparedInterpreterInstance(scenario, n)
        return measureCurrentThreadCpu(warmup, iters) { repeat(batch) { inst.run() } }
    }

    /** Preflight, build and prove eligibility, all outside any clock the callers above start. */
    private fun preparedInterpreterInstance(scenario: PerfScenario, n: Int): RunInstance {
        preflight(scenario, n)
        val engine = InterpreterPerfEngine.build(scenario)
        val inst = engine.instance(n)
        // Full outcome-bearing eligibility on the exact instance is outside the clock; it also primes the
        // profiling instance without projecting away `unsupported`.
        val outcome = try {
            inst.outcome()
        } catch (e: io.github.mbackschat.a12.dm.interpreter.UnsupportedConstructException) {
            throw IllegalStateException(
                "${scenario.name} (N=$n, variant=0): refusing to time — ${e.message}", e)
        }
        requireEligible(scenario, n, 0, outcome)
        return inst
    }

    /** Median PER-EVAL ms over [timedLoops] loops (each evaluating every instance once) after [warmupLoops]. */
    private fun measureLoop(warmupLoops: Int, timedLoops: Int, insts: List<RunInstance>): Stats {
        repeat(warmupLoops) { insts.forEach { it.run() } }
        val perEval = DoubleArray(timedLoops) {
            val t = System.nanoTime()
            insts.forEach { it.run() }
            (System.nanoTime() - t) / 1e6 / insts.size
        }
        return stats(perEval)
    }

    private fun stats(samples: DoubleArray): Stats {
        samples.sort()
        val p90 = samples[minOf(samples.size - 1, (samples.size * 9) / 10)]
        return Stats(samples[samples.size / 2], samples.first(), p90)
    }

    internal fun assertSignaturesAgree(
        scenario: String,
        n: Int,
        aLabel: String,
        a: List<String>,
        bLabel: String,
        b: List<String>,
    ) =
        check(a == b) {
            val aCounts = signatureCounts(a)
            val bCounts = signatureCounts(b)
            val onlyA = signatureDelta(aCounts, bCounts)
            val onlyB = signatureDelta(bCounts, aCounts)
            "$scenario (N=$n): $aLabel and $bLabel must agree on complete signatures.\n" +
                "  ${renderSignatureDelta(aLabel, onlyA)}\n" +
                "  ${renderSignatureDelta(bLabel, onlyB)}"
        }

    private fun signatureCounts(signatures: List<String>): Map<String, Int> =
        linkedMapOf<String, Int>().apply {
            signatures.forEach { signature -> merge(signature, 1, Int::plus) }
        }

    private fun signatureDelta(left: Map<String, Int>, right: Map<String, Int>): SignatureDelta {
        var occurrences = 0
        var distinct = 0
        val samples = mutableListOf<SignatureSample>()
        left.forEach { (signature, leftOccurrences) ->
            val excess = leftOccurrences - (right[signature] ?: 0)
            if (excess > 0) {
                occurrences += excess
                distinct++
                if (samples.size < SIGNATURE_SAMPLE_LIMIT) samples += SignatureSample(signature, excess)
            }
        }
        return SignatureDelta(occurrences, distinct, samples)
    }

    private fun renderSignatureDelta(label: String, delta: SignatureDelta): String {
        val renderedSamples = delta.samples.joinToString(prefix = "[", postfix = "]") { sample ->
            val multiplicity = if (sample.occurrences == 1) "" else " ×${sample.occurrences}"
            boundedSignature(sample.signature) + multiplicity
        }
        val omitted = delta.distinct - delta.samples.size
        val omittedSuffix = if (omitted == 0) "" else " ($omitted distinct sample(s) omitted)"
        return "only-$label(${delta.occurrences})=$renderedSamples$omittedSuffix"
    }

    private fun boundedSignature(signature: String): String =
        if (signature.length <= SIGNATURE_DISPLAY_LIMIT) signature
        else signature.take(SIGNATURE_DISPLAY_LIMIT - 1) + "…"

    /** A one-line single-document log row: interp vs the kernel baseline(s) + ratios (+ the Java tax when present). */
    fun format(row: PerfRow, caveat: String = ""): String =
        "[perf] ${row.identity.label} fingerprint=${row.identity.fingerprint} N=${row.n} firings=${row.firings}  " +
            "${triple(row.interp, row.groovy, row.java, row.ratioGroovy, row.ratioJava, row.groovyTax)}  $caveat"

    /** A one-line repeated-eval log row (per-eval, engine reused). */
    fun formatRepeated(row: RepeatedRow, caveat: String = ""): String =
        "[perf] ${row.identity.label} fingerprint=${row.identity.fingerprint} REPEATED N=${row.n} " +
            "×${row.variants} variants (per-eval)  " +
            "${triple(row.interp, row.groovy, row.java, row.ratioGroovy, row.ratioJava, row.groovyTax)}  $caveat"

    private fun triple(interp: Stats, groovy: Stats?, java: Stats?, ratioGroovy: Double?, ratioJava: Double?, groovyTax: Double?): String =
        "interp=${ms(interp.medianMs)} (min ${ms(interp.minMs)})  " +
            (if (groovy != null) "groovy=${ms(groovy.medianMs)}  ratioG=${"%.2f".format(Locale.ROOT, ratioGroovy)}  " else "") +
            (if (java != null) "java=${ms(java.medianMs)}  ratioJava=${"%.2f".format(Locale.ROOT, ratioJava)}  groovyTax=${"%.2f".format(Locale.ROOT, groovyTax)}×" else "")

    private fun ms(v: Double): String = "%.2fms".format(Locale.ROOT, v)
}
