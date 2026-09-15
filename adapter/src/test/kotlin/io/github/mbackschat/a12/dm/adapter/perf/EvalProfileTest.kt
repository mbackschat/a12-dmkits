package io.github.mbackschat.a12.dm.adapter.perf

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.gen.asSink
import io.github.mbackschat.a12.dm.interpreter.model.RelevantEntity
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty

/**
 * **Profiling driver** (INTERPRETER-PERFORMANCE.md #64). Runs `validateFull` on the low-firing rule
 * scenario in a tight loop for ~[SECONDS]s so a sampling profiler gets
 * dense samples — to attribute `validateFull`'s cost (eval / `firedType` vs the shared machinery:
 * `iterationContexts`, the per-(rule,row) `Interpreter` alloc, the suppressor scan, `Document` lookups). Run:
 * ```
 * ./build.sh :adapter:test --tests "*EvalProfileTest" -Dperf.profile=true -Dperf.rules=384 -Dperf.jfr=<abs>/prof.jfr
 * jfr print --events jdk.ExecutionSample --stack-depth 30 <abs>/prof.jfr   # then aggregate the hot frames
 * ```
 * It is opt-in (`perf.profile`) and asserts nothing — it exists to feed the profiler, not to gate.
 */
@EnabledIfSystemProperty(named = "perf.profile", matches = "true")
class EvalProfileTest {

    @Test
    fun loopLowFiringForProfiler() {
        val rules = System.getProperty("perf.rules", "192").toInt()
        profile(
            PerfCatalog.manyRules(rules, firing = false),
            System.getProperty("perf.n", "1000").toInt(),
            "low-firing $rules",
        )
    }

    /** Profile the DEEP+RICH scenario (the one that exposed a super-linear cliff) at `-Dperf.n` vendors. */
    @Test
    fun loopDeepRichForProfiler() = profile(PerfCatalog.deepRich(firing = false),
        System.getProperty("perf.n", "500").toInt(), "deepRich")

    /**
     * The two thinnest VALIDATE rows against the kernel, so a hot path found on one scenario can be checked for
     * being shared before it is optimized. `CO-correlation` is thinner still but is a COMPUTE scenario, which this
     * driver cannot reach — [ComputeOverlayProfileTest] is its sibling.
     */
    @Test
    fun loopParallelFramedForProfiler() = profile(PerfCatalog.parallelFramed(),
        System.getProperty("perf.n", "1000").toInt(), "PF-framed")

    @Test
    fun loopComplexRealWorldForProfiler() = profile(PerfCatalog.complexRealWorldGate(firing = false),
        System.getProperty("perf.n", "240").toInt(), "complexRealWorld")

    /**
     * **PARTIAL validation over a semantic-index model** — the driver IG126 was reopened for.
     *
     * Three of the four memos that reopened it cannot be reached by the two existing drivers *by construction*:
     * `ValidationPass.indexColumns` says of itself "Empty and unread in a FULL validation", `decided` is the same
     * shape, and the group-relevance map is built by `groupRelevanceOf`, which only `validatePart` calls. Reading a
     * 0 for them off a full-validation profile was the defect [IF285](../../../../../../../../../../../docs/INTERPRETER-FINDINGS.md) corrected —
     * a measurement whose denominator was never checked — so measuring them needs this path, not a longer run of
     * the old one.
     *
     * Two choices, both deliberate. The scenario is `semanticIndexPerRow` because `indexColumns` memoizes an INDEX
     * COLUMN and a model without a semantic-index gate would leave it empty for a second, unrelated reason. And the
     * relevant set wildcards every repetition (`RelevantEntity.anyRep`), which makes the whole document relevant:
     * that maximizes CONSULTATIONS of each memo, which is the quantity in question, while still running the partial
     * code path (`ValidationPass.partial` keys on `config.cellRelevant != null`, not on the subset's size). A
     * narrower set would measure less of the memo, not more of it.
     */
    @Test
    fun loopPartialSemanticIndexForProfiler() =
        profilePartial(PerfCatalog.semanticIndexPerRow(),
            System.getProperty("perf.n", "1000").toInt(), "semanticIndex")

    /**
     * **PARTIAL validation over the memo probe** — the driver for the three memos [IF290] closed, which the
     * semantic-index driver above cannot reach: `starRelevantOf`'s `decided` needs a starred operand evaluated in
     * partial mode, `groupRelevanceOf`'s cache is built only by `validatePart`, and `RepetitionNotUniqueEvaluator`
     * needs an RNU rule. [PerfCatalog.partialMemoProbe] carries both constructs and populates real duplicate
     * clusters, so a 0 read here is a measurement rather than a scenario that could not execute the code.
     */
    @Test
    fun loopPartialMemoProbeForProfiler() =
        profilePartial(PerfCatalog.partialMemoProbe(), System.getProperty("perf.n", "1000").toInt(), "memo-probe")

    private fun profilePartial(scenario: PerfScenario, n: Int, label: String) {
        val measurement = PerfHarness.measurementLabel(scenario, n)
        val model = scenario.interpModel
        val doc = model.newDocument()
        scenario.instance(doc.asSink(), n, 0)
        val interp = DmInterpreter(model)
        // Derived from the model rather than a path literal, so a driver cannot drift from the scenario it
        // profiles: every declared field, at every repetition. Wildcarding every repetition maximizes the
        // CONSULTATIONS of each per-call memo — the quantity these drivers exist to measure — while still running
        // the partial code path, which keys on `config.cellRelevant != null` rather than on the subset's size.
        val relevant = model.fields.map { RelevantEntity.anyRep(it.path) }.toSet()
        repeat(20) { interp.validatePart(doc, relevant) }
        val deadline = System.nanoTime() + SECONDS * 1_000_000_000L
        var loops = 0
        while (System.nanoTime() < deadline) { interp.validatePart(doc, relevant); loops++ }
        println("[profile] $measurement partial-$label @ N=$n: $loops validatePart calls in ~${SECONDS}s")
    }

    private fun profile(scenario: io.github.mbackschat.a12.dm.adapter.perf.PerfScenario, n: Int, label: String) {
        val measurement = PerfHarness.measurementLabel(scenario, n)
        val model = scenario.interpModel
        val doc = model.newDocument()
        scenario.instance(doc.asSink(), n, 0)
        val interp = DmInterpreter(model)
        repeat(20) { interp.validateFull(doc) }   // warm + let JFR start sampling
        val deadline = System.nanoTime() + SECONDS * 1_000_000_000L
        var loops = 0
        while (System.nanoTime() < deadline) { interp.validateFull(doc); loops++ }
        println("[profile] $measurement $label @ N=$n: $loops validateFull calls in ~${SECONDS}s")
    }

    private companion object { const val SECONDS = 20 }
}
