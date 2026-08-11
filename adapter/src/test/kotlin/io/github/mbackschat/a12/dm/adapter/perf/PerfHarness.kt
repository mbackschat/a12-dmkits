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

/** The result of the IF137-ratified [PerfHarness.pairedGrowth] estimator: the median within-pair growth [ratio]
 *  and a representative [smallMs]/[largeMs] (the medians across pairs) for the log line. */
data class Growth(val ratio: Double, val smallMs: Double, val largeMs: Double)

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

    /** Time [block], streaming a `<label> …` line before and the `<label> = <ms>` after (live [progress]). */
    inline fun <T> step(label: String, block: () -> T): T {
        progress("[perf] $label …")
        val t = System.nanoTime()
        val r = block()
        progress("[perf] $label — done in %.1fs".format((System.nanoTime() - t) / 1e9))
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
     * IF137). Reported [Growth.smallMs]/[Growth.largeMs] are the medians across pairs, for the log line.
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
        return Growth(median(ratios), median(smalls), median(larges))
    }

    /**
     * Assert a growth estimate stays under a CATASTROPHE floor, **re-measuring before it fails**.
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
        estimate: () -> Growth,
    ): Growth {
        require(attempts > 0 && attempts % 2 == 1) { "attempts must be a positive odd number" }
        val seen = mutableListOf(estimate())
        if (seen.single().ratio < floor) return seen.single()   // clean first estimate: one measurement, as before
        while (seen.size < attempts) seen += estimate()  // a breach must survive the MEDIAN of independent re-runs
        // The ACCEPTED estimate is returned so a caller can anchor its absolute medians without paying to
        // re-measure them (PerfAnchors) — the same numbers acceptance was decided on, never a fresh sample.
        val accepted = seen.sortedBy { it.ratio }[seen.size / 2]
        if (accepted.ratio < floor) return accepted
        throw AssertionError(
            "$label median within-pair N/4N growth stayed at or above the ${floor}x catastrophe floor across " +
                "$attempts independent re-measurements: " +
                seen.joinToString(", ") { "${it.ratio} (${it.smallMs}ms -> ${it.largeMs}ms)" } +
                ". Acceptance is the median of these, so one low outlier cannot clear a real breach. Load " +
                "inflates one estimate and does not reproduce; an algorithmic blowup does (linear medians " +
                "~4x, quadratic ~16x)." + culprit?.let { " Likely cause: $it." }.orEmpty(),
        )
    }

    /** Independent re-measurements a catastrophe floor demands before it calls a breach real. */
    const val GROWTH_RETRY_ATTEMPTS = 3

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
    ): PairedPerfCurve {
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

        val iSmallMs = DoubleArray(pairs)
        val gSmallMs = DoubleArray(pairs)
        val jSmallMs = DoubleArray(pairs)
        val iLargeMs = DoubleArray(pairs)
        val gLargeMs = DoubleArray(pairs)
        val jLargeMs = DoubleArray(pairs)
        for (pair in 0 until pairs) {
            gSmallMs[pair] = measure(warmup, iters) { gSmall.run() }.medianMs
            jSmallMs[pair] = measure(warmup, iters) { jSmall.run() }.medianMs
            iSmallMs[pair] = measure(warmup, iters) { iSmall.run() }.medianMs
            gLargeMs[pair] = measure(warmup, iters) { gLarge.run() }.medianMs
            jLargeMs[pair] = measure(warmup, iters) { jLarge.run() }.medianMs
            iLargeMs[pair] = measure(warmup, iters) { iLarge.run() }.medianMs
        }

        val smallRow = PerfRow(
            scenario.completeIdentity(small, listOf(smallResult.size), listOf(0)),
            small,
            smallResult.size,
            stats(iSmallMs.copyOf()),
            stats(gSmallMs.copyOf()),
            stats(jSmallMs.copyOf()),
        )
        val largeRow = PerfRow(
            scenario.completeIdentity(large, listOf(largeResult.size), listOf(0)),
            large,
            largeResult.size,
            stats(iLargeMs.copyOf()),
            stats(gLargeMs.copyOf()),
            stats(jLargeMs.copyOf()),
        )
        val iGrowth = growth(iSmallMs, iLargeMs, small, large)
        val gGrowth = growth(gSmallMs, gLargeMs, small, large)
        val jGrowth = growth(jSmallMs, jLargeMs, small, large)
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
        return PairedPerfCurve(smallRow, largeRow, iGrowth, gGrowth, jGrowth)
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
                "ratioGroovy=${"%.2f".format(groovy)}, ratioJava=${"%.2f".format(java)}"
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

    /** Time the INTERPRETER only at scale [n] (profile/scaling use — no kernel, so high N stays cheap). */
    fun timeInterpreter(scenario: PerfScenario, n: Int, warmup: Int, iters: Int): Stats {
        val inst = preparedInterpreterInstance(scenario, n)
        return measure(warmup, iters) { inst.run() }
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
    fun timeInterpreterCpu(scenario: PerfScenario, n: Int, warmup: Int, iters: Int): Stats {
        val inst = preparedInterpreterInstance(scenario, n)
        return measureCurrentThreadCpu(warmup, iters) { inst.run() }
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
            (if (groovy != null) "groovy=${ms(groovy.medianMs)}  ratioG=${"%.2f".format(ratioGroovy)}  " else "") +
            (if (java != null) "java=${ms(java.medianMs)}  ratioJava=${"%.2f".format(ratioJava)}  groovyTax=${"%.2f".format(groovyTax)}×" else "")

    private fun ms(v: Double): String = "%.2fms".format(v)
}
