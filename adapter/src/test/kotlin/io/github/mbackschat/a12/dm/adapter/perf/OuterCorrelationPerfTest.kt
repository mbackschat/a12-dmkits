package io.github.mbackschat.a12.dm.adapter.perf

import io.github.mbackschat.a12.dm.adapter.laws.RuntimeLaws
import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * Perf gate — the two `$` self-correlation regimes, each measured AGAINST the kernel's same operation on the SAME
 * model:
 *
 * - **Equality** (`[Count] == [$Count]`, IF47): recognized as a HASH JOIN — O(N) total, not a nested O(N²) scan.
 *   Four times the rows must cost well below quadratic growth (≈16×); the generous `<9×` catastrophe floor catches
 *   loss of the hash-join path while tolerating timing noise.
 * - **Comparator-only non-equality** (`[Count] > [$Count]`, IG77 → IF159): an ordered correlation plan must keep
 *   the full interpreter curve sub-quadratic. The all-equal `flat` Count is the no-witness discriminator that made
 *   the former nested fallback scan every pair. Complete interpreter/dynamic-Groovy/generated-Java signatures are
 *   checked on the exact N and 4N documents before timing; the kernel curve remains informative rather than making
 *   kernel-equivalent slowness a success criterion.
 *
 * **The model repeats FAR beyond the benchmark N** (built here, not `order.dm.json`, whose Items cap of 50 is
 * below N — the kernel would treat over-limit rows as invalid context and skip rule eval on them, making the
 * against-kernel comparison a fiction). ONE model is loaded into both engines (the kernel via
 * [RuntimeLaws.kernelEngine], the interpreter via [ModelLoader.load]) so they evaluate the identical rule at scale.
 * Robust estimator (IF137 paired: interleaved N/4N, median of within-pair ratios) mirrors `PerfGateTest`.
 */
@Tag("perf")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OuterCorrelationPerfTest {

    private val base = DocumentModelDraft.named("order").locale("en_US")
        .rootGroup("Order") { o ->
            o.group("Items") { it -> it.repeatable(MAX).numberField("Count") { c -> c.fractionDigits(0, 0) } }
        }.build().toJson()
    private val m = BoundModel.ofJson(base)

    private fun selfExcluded() = Dsl.currentRepetition(m.group("/Order/Items"))
        .ne(Dsl.currentRepetition(m.group("/Order/Items"), true))

    // FieldFilled(Count) And AtLeastOneFieldFilled(Items* / Count Having self-exclusion And [Count] == [$Count]).
    private val corrEq = DslRenderer().render(m.field(COUNT).filled().and(
        Dsl.atLeastOneFieldFilled(m.flatten("/Order/Items*/Count").having(
            selfExcluded().and(m.num(COUNT).eq(m.num(COUNT).outer()))))))

    // …the SAME shape with `>` instead of `==` — the ordered comparator-correlation discriminator.
    private val corrGt = DslRenderer().render(m.field(COUNT).filled().and(
        Dsl.atLeastOneFieldFilled(m.flatten("/Order/Items*/Count").having(
            selfExcluded().and(m.num(COUNT).gt(m.num(COUNT).outer()))))))

    private val kEqModel = RuntimeLaws.withRule(base, "/Order", "Corr", "../Items/Count", "CORR", corrEq)
    private val kGtModel = RuntimeLaws.withRule(base, "/Order", "CorrGt", "../Items/Count", "CORRGT", corrGt)
    private val defEq = ModelLoader.load(kEqModel)
    private val defGt = ModelLoader.load(kGtModel)
    private val kernelEq = RuntimeLaws.kernelEngine(kEqModel)   // Groovy codegen once
    private val kernelGt = RuntimeLaws.kernelEngine(kGtModel)
    private val eqScenario = PerfScenario("equality \$ correlation", kEqModel, defEq) { sink, n, _ ->
        sink.group("/Order", 1)
        for (r in 1..n) {
            sink.group("/Order/Items", 1, r)
            sink.field(COUNT, spread(r), 1, r, 1)
        }
    }
    private val gtScenario = PerfScenario("ordered \$ non-equality correlation", kGtModel, defGt) { sink, n, _ ->
        sink.group("/Order", 1)
        for (r in 1..n) {
            sink.group("/Order/Items", 1, r)
            sink.field(COUNT, flat(r), 1, r, 1)
        }
    }

    @BeforeAll
    fun assertExactTriEngineEligibility() {
        for (scenario in listOf(eqScenario, gtScenario)) {
            PerfHarness.assertSemanticAgreement(scenario, listOf(N, N * 4), withJava = true)
        }
    }

    /** Distinct Counts (mod 50) — the equality regime has real join buckets. */
    private fun spread(r: Int) = (r % 50).toString()

    /** All-equal Counts — the non-equality `>` never holds, so no witness exists and neither engine early-cancels. */
    private fun flat(@Suppress("UNUSED_PARAMETER") r: Int) = "5"

    private fun doc(def: ModelDef, n: Int, count: (Int) -> String): Document {
        val d = def.newDocument()
        for (r in 1..n) d.field(COUNT, count(r), 1, r, 1)
        return d
    }

    private fun kDoc(n: Int, count: (Int) -> String): RuntimeLaws.Doc {
        val d = RuntimeLaws.doc().group("/Order", 1)
        for (r in 1..n) d.group("/Order/Items", 1, r).field(COUNT, count(r), 1, r, 1)
        return d
    }

    private fun medianMs(def: ModelDef, n: Int, count: (Int) -> String): Double {
        val interp = DmInterpreter(def)
        val d = doc(def, n, count)
        return PerfHarness.measure(warmup = 2, iters = 3) { interp.validateFull(d) }.medianMs
    }

    private fun kernelMedianMs(engine: RuntimeLaws.KernelEngine, n: Int, count: (Int) -> String): Double {
        val instance = engine.instance(kDoc(n, count))          // build the IDocument once, outside timing
        return PerfHarness.measure(warmup = 2, iters = 3) {
            engine.firedCodesSorted(instance)
        }.medianMs
    }

    @Test
    fun equalityOuterCorrelationStaysSubQuadratic() {
        // IF137 paired estimator: interleaved N/4N, median of within-pair ratios (via the shared PerfHarness helper).
        val g = PerfHarness.pairedGrowth(N, N * 4) { medianMs(defEq, it, ::spread) }
        println("[perf] %s → %s \$ equality correlation: %.1fms@%d → %.1fms@%d  median-growth=%.1f× (linear≈4, quadratic≈16)"
            .format(
                PerfHarness.measurementLabel(eqScenario, N),
                PerfHarness.measurementLabel(eqScenario, N * 4),
                g.smallMs, N, g.largeMs, N * 4, g.ratio,
            ))
        assertTrue(g.ratio < PerfBounds.SUB_QUADRATIC_MAX,
            "\$ equality correlation scaling looks quadratic: 4× the rows took ${g.ratio}× the time (linear≈4, quadratic≈16)")
    }

    /**
     * **Measured against the kernel** (INTERPRETER-PERFORMANCE "measure AGAINST the kernel"): times BOTH engines
     * at N and 4N and asserts the interp/kernel RATIO is stable. This does not assign the kernel a complexity
     * class; it catches the interpreter worsening relative to the actual oracle curve as N grows.
     */
    @Test
    fun equalityOuterCorrelationDoesNotWorsenAgainstKernel() {
        val (iSmall, kSmall) = medianMs(defEq, N, ::spread) to kernelMedianMs(kernelEq, N, ::spread)
        val (iLarge, kLarge) = medianMs(defEq, N * 4, ::spread) to kernelMedianMs(kernelEq, N * 4, ::spread)
        val ratioSmall = iSmall / kSmall
        val ratioLarge = iLarge / kLarge
        println("[perf] %s | %s \$ correlation vs kernel: N=%d interp=%.1f kernel=%.1f (%.2f×) | N=%d interp=%.1f kernel=%.1f (%.2f×)"
            .format(
                PerfHarness.measurementLabel(eqScenario, N),
                PerfHarness.measurementLabel(eqScenario, N * 4),
                N, iSmall, kSmall, ratioSmall, N * 4, iLarge, kLarge, ratioLarge,
            ))
        // The ratio must not GROW materially with N. Generous 2.5× headroom for timing noise; a reverted nested
        // interpreter scan should widen it materially at 4× rows.
        assertTrue(ratioLarge < ratioSmall * 2.5,
            "interp/kernel ratio grew from $ratioSmall× to $ratioLarge× at 4× rows — interp is a worse class than the kernel")
    }

    /** The `>` no-witness discriminator must be exact at both scales and stay below the shared quadratic trip. */
    @Test
    fun nonEqualityOuterCorrelationIsExactAndSubQuadratic() {
        val g = PerfHarness.pairedGrowth(N, N * 4) { medianMs(defGt, it, ::flat) }
        println("[perf] %s → %s \$ non-equality correlation: %.1fms@%d → %.1fms@%d  median-growth=%.1f× (linear≈4, quadratic≈16)"
            .format(
                PerfHarness.measurementLabel(gtScenario, N),
                PerfHarness.measurementLabel(gtScenario, N * 4),
                g.smallMs, N, g.largeMs, N * 4, g.ratio,
            ))
        assertTrue(g.ratio < PerfBounds.SUB_QUADRATIC_MAX,
            "\$ non-equality correlation scaling looks quadratic: 4× the rows took ${g.ratio}× the time")

        // Informative oracle curve only: the kernel currently evaluates this arbitrary Having with its own scan.
        // The interpreter's success criterion is the sub-quadratic curve above, never "as slow as the kernel".
        val (iSmall, kSmall) = medianMs(defGt, N, ::flat) to kernelMedianMs(kernelGt, N, ::flat)
        val (iLarge, kLarge) = medianMs(defGt, N * 4, ::flat) to kernelMedianMs(kernelGt, N * 4, ::flat)
        val ratioSmall = iSmall / kSmall
        val ratioLarge = iLarge / kLarge
        println("[perf] %s | %s \$ non-equality vs kernel: N=%d interp=%.1f kernel=%.1f (%.2f×) | N=%d interp=%.1f kernel=%.1f (%.2f×)"
            .format(
                PerfHarness.measurementLabel(gtScenario, N),
                PerfHarness.measurementLabel(gtScenario, N * 4),
                N, iSmall, kSmall, ratioSmall, N * 4, iLarge, kLarge, ratioLarge,
            ))
    }

    private companion object {
        const val N = 400
        const val MAX = 200_000   // Items repeatability — FAR above 4×N, so the kernel evaluates the correlation at scale
        const val COUNT = "/Order/Items/Count"
    }
}
