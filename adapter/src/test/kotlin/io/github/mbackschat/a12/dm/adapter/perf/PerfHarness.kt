package io.github.mbackschat.a12.dm.adapter.perf

import io.github.mbackschat.a12.dm.adapter.laws.RuntimeLaws
import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.gen.PlacementSink
import io.github.mbackschat.a12.dm.interpreter.gen.asSink
import io.github.mbackschat.a12.dm.interpreter.model.ComputeOutcome
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef

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
 *   and the mode that exposes the interpreter's per-call rule re-parse (the #31 "prepare once" cost).
 *
 * **Correctness first (a named non-negotiable):** both modes assert the engines produce the SAME fired codes
 * (a sorted *multiset* — same codes AND per-row firing count) BEFORE timing; a fast-but-wrong path is no win.
 * The row-level differential stays the `*DiffTest` (in `laws/`) job; here it gates that the benchmarked
 * instance is evaluated equivalently.
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
class PerfScenario(
    val name: String,
    /** The base DM-JSON with the scenario's rule(s) injected — the kernel's model (`RuntimeLaws.withRule`). */
    val kernelModel: String,
    /** The same model as a [ModelDef] — the interpreter's model. */
    val interpModel: ModelDef,
    /** Whether this scenario times `validateFull` or `compute` (default validate). */
    val op: PerfOp = PerfOp.VALIDATE,
    /**
     * Build the instance at scale [n], perturbed by [variant], into [sink]. `variant = 0` is the canonical
     * instance; a non-zero variant shifts a value so the document differs slightly (mimicking a form edit /
     * the next batch record) — used by [PerfHarness.runRepeated].
     */
    val instance: (sink: PlacementSink, n: Int, variant: Int) -> Unit,
)

/** A timed runnable bound to ONE materialized instance — built outside timing; [run] is the timed op. */
fun interface RunInstance {
    /** The timed op's result: the fired codes, sorted (the correctness payload AND what is timed). */
    fun run(): List<String>
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
        val interp = DmInterpreter(model)   // built once; note: re-parses rules per validate call (the #31 cost)
        return object : ReusableEngine {
            override val label = "interp"
            override fun instance(n: Int, variant: Int): RunInstance {
                val doc = Document(model.kinds, model.declaredReps, model.unsignedFields, model.rangeConfig, model.dateFormats, model.scales)
                scenario.instance(doc.asSink(), n, variant)
                return RunInstance {
                    when (scenario.op) {
                        PerfOp.VALIDATE -> interp.validateFull(doc).map { it.code }.sorted()
                        PerfOp.COMPUTE -> interp.compute(doc).map {
                            if (it.outcome == ComputeOutcome.VALUE) "${it.path}|${it.value}" else "${it.path}|${it.outcome}"
                        }.sorted()
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
        return object : ReusableEngine {
            override val label = "groovy"
            override fun instance(n: Int, variant: Int): RunInstance {
                val builder = RuntimeLaws.doc()
                scenario.instance(KernelDocSink(builder), n, variant)
                val instance = engine.instance(builder)   // build the IDocument once, outside timing
                return RunInstance {
                    when (scenario.op) {
                        PerfOp.VALIDATE -> engine.firedCodesSorted(instance)
                        PerfOp.COMPUTE -> engine.computeOutcomesSorted(instance)
                    }
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
        return object : ReusableEngine {
            override val label = "java"
            override fun instance(n: Int, variant: Int): RunInstance {
                val builder = RuntimeLaws.doc()
                scenario.instance(KernelDocSink(builder), n, variant)
                val instance = engine.instance(builder)   // build the IDocument once, outside timing
                return RunInstance {
                    when (scenario.op) {
                        PerfOp.VALIDATE -> engine.firedCodesSorted(instance)
                        PerfOp.COMPUTE -> engine.computeOutcomesSorted(instance)
                    }
                }
            }
        }
    }
}

/** Feeds a [PerfScenario]'s placements into a [RuntimeLaws.Doc] — shared by both kernel engines (Groovy + Java). */
private class KernelDocSink(private val doc: RuntimeLaws.Doc) : PlacementSink {
    override fun group(path: String, vararg reps: Int) { doc.group(path, *reps) }
    override fun field(path: String, value: String, vararg reps: Int) { doc.field(path, value, *reps) }
    override fun emptyField(path: String, vararg reps: Int) { doc.emptyField(path, *reps) }
}

/** Wall-clock distribution (ms) of a timed operation — robust stats, not a single cold number. */
data class Stats(val medianMs: Double, val minMs: Double, val p90Ms: Double)

/**
 * One single-document scenario row: the interpreter's stats at scale [n] vs BOTH kernel baselines — [groovy]
 * (dynamic, the production path) and [java] (the kernel's own validator compiled as Java, the Groovy-tax-free
 * baseline). The **[ratioJava]** is the *fair* algorithmic comparison; [ratioGroovy] is tax-inflated; [groovyTax]
 * is how much slower Groovy execution is than Java for this scenario.
 */
data class PerfRow(
    val scenario: String, val n: Int, val firings: Int,
    val interp: Stats, val groovy: Stats?, val java: Stats?,
) {
    /** interp_median / groovy_median — tax-inflated (Groovy is slower than Java; read with the fairness caveat). */
    val ratioGroovy: Double? get() = groovy?.let { interp.medianMs / it.medianMs }
    /** interp_median / java_median — the FAIR algorithmic ratio (kernel logic, Groovy tax removed). */
    val ratioJava: Double? get() = java?.let { interp.medianMs / it.medianMs }
    /** groovy_median / java_median — the Groovy execution tax (≥ 1 means Groovy is slower than the same logic in Java). */
    val groovyTax: Double? get() = if (groovy != null && java != null) groovy.medianMs / java.medianMs else null
}

/** One repeated-eval row: engine built ONCE, [variants] varying instances looped — PER-EVAL stats + the two ratios. */
data class RepeatedRow(
    val scenario: String, val n: Int, val variants: Int,
    val interp: Stats, val groovy: Stats?, val java: Stats?,
) {
    val ratioGroovy: Double? get() = groovy?.let { interp.medianMs / it.medianMs }
    val ratioJava: Double? get() = java?.let { interp.medianMs / it.medianMs }
    val groovyTax: Double? get() = if (groovy != null && java != null) groovy.medianMs / java.medianMs else null
}

object PerfHarness {

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
     * Single-document mode: build all three engines (interp, kernel-Groovy, kernel-Java), materialize the
     * canonical instance (variant 0) for each, assert the fired-code multisets agree THREE-way (correctness
     * first — interp == groovy == java), then time each. The O(N²) gate + the Groovy-vs-Java fairness measure.
     * Pass [withJava] = false to skip the (compile-cost) Java baseline when only the interp/Groovy gate is wanted.
     */
    fun runScenario(scenario: PerfScenario, n: Int, warmup: Int = 12, iters: Int = 8, withJava: Boolean = true): PerfRow {
        val iInst = InterpreterPerfEngine.build(scenario).instance(n)
        val gInst = KernelPerfEngine.build(scenario).instance(n)
        val jInst = if (withJava) KernelJavaPerfEngine.build(scenario).instance(n) else null
        val iResult = iInst.run()
        assertAgree(scenario.name, n, "interp", iResult, "groovy", gInst.run())
        if (jInst != null) assertAgree(scenario.name, n, "groovy", gInst.run(), "java", jInst.run())
        val gStats = measure(warmup, iters) { gInst.run() }
        val jStats = jInst?.let { measure(warmup, iters) { it.run() } }
        val iStats = measure(warmup, iters) { iInst.run() }
        return PerfRow(scenario.name, n, iResult.size, iStats, gStats, jStats)
    }

    /**
     * **Repeated-eval mode (form-input / batch).** Build each engine ONCE, pre-build [variants] slightly-
     * different instances (variant 0..M-1, OUTSIDE timing), assert correctness on the first + last variant,
     * then time the LOOP of M evaluations and report **per-eval** stats. This is the reuse pattern the kernel's
     * codegen + per-run caches are tuned for; comparing its ratio to the single-document [runScenario] ratio
     * reveals whether the interpreter's per-call rule re-parse (the #31 "prepare once" cost) hurts under reuse.
     */
    fun runRepeated(
        scenario: PerfScenario, n: Int, variants: Int = 20, warmupLoops: Int = 3, timedLoops: Int = 5,
        withJava: Boolean = true,
    ): RepeatedRow {
        val iEng = InterpreterPerfEngine.build(scenario)
        val gEng = KernelPerfEngine.build(scenario)
        val jEng = if (withJava) KernelJavaPerfEngine.build(scenario) else null
        val iInsts = (0 until variants).map { iEng.instance(n, it) }
        val gInsts = (0 until variants).map { gEng.instance(n, it) }
        val jInsts = jEng?.let { e -> (0 until variants).map { e.instance(n, it) } }
        // Correctness on the extremes — the perturbation shifts firings, so first != last exercises the variety.
        assertAgree(scenario.name, n, "interp", iInsts.first().run(), "groovy", gInsts.first().run())
        assertAgree(scenario.name, n, "interp", iInsts.last().run(), "groovy", gInsts.last().run())
        if (jInsts != null) assertAgree(scenario.name, n, "groovy", gInsts.first().run(), "java", jInsts.first().run())
        val gStats = measureLoop(warmupLoops, timedLoops, gInsts)
        val jStats = jInsts?.let { measureLoop(warmupLoops, timedLoops, it) }
        val iStats = measureLoop(warmupLoops, timedLoops, iInsts)
        return RepeatedRow(scenario.name, n, variants, iStats, gStats, jStats)
    }

    /** Time the INTERPRETER only at scale [n] (profile/scaling use — no kernel, so high N stays cheap). */
    fun timeInterpreter(scenario: PerfScenario, n: Int, warmup: Int, iters: Int): Stats {
        val inst = InterpreterPerfEngine.build(scenario).instance(n)
        inst.run()   // correctness is the gate's job; here we only profile the engine
        return measure(warmup, iters) { inst.run() }
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

    private fun assertAgree(scenario: String, n: Int, aLabel: String, a: List<String>, bLabel: String, b: List<String>) =
        check(a == b) {
            "$scenario (N=$n): $aLabel and $bLabel must agree on fired codes.\n" +
                "  $bLabel(${b.size})=${b.distinct()}\n  $aLabel(${a.size})=${a.distinct()}"
        }

    /** A one-line single-document log row: interp vs both kernel baselines + the fair (java) ratio + Groovy tax. */
    fun format(row: PerfRow, caveat: String = ""): String =
        "[perf] ${row.scenario} N=${row.n} firings=${row.firings}  ${triple(row.interp, row.groovy, row.java, row.ratioJava, row.groovyTax)}  $caveat"

    /** A one-line repeated-eval log row (per-eval, engine reused). */
    fun formatRepeated(row: RepeatedRow, caveat: String = ""): String =
        "[perf] ${row.scenario} REPEATED N=${row.n} ×${row.variants} variants (per-eval)  " +
            "${triple(row.interp, row.groovy, row.java, row.ratioJava, row.groovyTax)}  $caveat"

    private fun triple(interp: Stats, groovy: Stats?, java: Stats?, ratioJava: Double?, groovyTax: Double?): String =
        "interp=${ms(interp.medianMs)} (min ${ms(interp.minMs)})  " +
            (if (groovy != null) "groovy=${ms(groovy.medianMs)}  " else "") +
            (if (java != null) "java=${ms(java.medianMs)}  ratioJava=${"%.2f".format(ratioJava)}  " else "") +
            (if (groovyTax != null) "groovyTax=${"%.2f".format(groovyTax)}×" else "")

    private fun ms(v: Double): String = "%.2fms".format(v)
}
