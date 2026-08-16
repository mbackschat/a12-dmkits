package io.github.mbackschat.a12.dm.adapter.perf

import io.github.mbackschat.a12.dm.adapter.laws.RuntimeLaws
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer
import java.util.Locale
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * Perf gate — a group-SCOPED field-fill quantifier over a large repeatable group stays **linear** (IF49). The
 * scope `/Order/Items*` expands to every descendant field cell across all rows, so a naive tally could be
 * O(rows·fields); this asserts sub-quadratic growth (4× the rows ≪ 16× the time) AND that the interpreter stays
 * in the kernel's complexity class (the interp/kernel ratio must not grow with N). Mirrors `OuterCorrelationPerfTest`.
 *
 * **The model repeats FAR beyond the benchmark N** (built here, not `order.dm.json`, whose Items cap of 50 is
 * below N — the kernel would treat over-limit rows as invalid context and skip rule eval on them, so the
 * against-kernel comparison would measure the kernel's context-error overhead, not group-fill). ONE model is
 * loaded into both engines so they evaluate the identical rule at scale.
 */
@Tag("perf")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GroupScopedFillPerfTest {

    private val base = DocumentModelDraft.named("order").locale("en_US")
        .rootGroup("Order") { o ->
            o.stringField("CustomerName") { }
            o.group("Items") { it -> it.repeatable(MAX).stringField("Sku") { } }
        }.build().toJson()
    private val m = BoundModel.ofJson(base)

    // FieldFilled(CustomerName) And AtLeastOneFieldFilled(/Order/Items*)  — the instantiated-range tally over all cells.
    private val cond = DslRenderer().render(m.field(CN).filled().and(Dsl.atLeastOneFieldFilled(m.flattenGroup("/Order/Items*"))))

    private val kModel = RuntimeLaws.withRule(base, "/Order", "R", "../CustomerName", "NF", cond)
    private val def: ModelDef = ModelLoader.load(kModel)
    private val kernelEngine = RuntimeLaws.kernelEngine(kModel)
    private val scenario = PerfScenario("group-scoped-fill", kModel, def) { sink, n, _ ->
        sink.group("/Order", 1)
        sink.field(CN, "Acme", 1, 1)
        for (r in 1..n) {
            sink.group("/Order/Items", 1, r)
            sink.field(SKU, "S$r", 1, r, 1)
        }
    }

    @BeforeAll
    fun assertExactTriEngineEligibility() {
        PerfHarness.assertSemanticAgreement(scenario, listOf(N, N * 4), withJava = true)
    }

    private fun kDoc(n: Int): RuntimeLaws.Doc {
        val d = RuntimeLaws.doc().group("/Order", 1).field(CN, "Acme", 1, 1)
        for (r in 1..n) d.group("/Order/Items", 1, r).field(SKU, "S$r", 1, r, 1)
        return d
    }

    /**
     * `warmup = 5, iters = 5`, raised from `2, 3` on 2026-08-13. At three samples the median is one middle value,
     * so a single contended slice moves it; measured over three sequential isolated gates this lane spanned
     * 1.68x (`[7.28, 7.27, 12.21]` at n=8000) — two tight readings and one outlier, which is the shape a
     * three-sample median cannot absorb. The deeper warmup also matters here because the first gate run after a
     * rebuild was systematically the slowest of the three.
     */
    private fun medianMs(n: Int, batch: Int = 1): Double =
        PerfHarness.timeInterpreter(scenario, n, warmup = 5, iters = 5, batch = batch).medianMs

    private fun kernelMedianMs(n: Int): Double {
        val instance = kernelEngine.instance(kDoc(n))
        return PerfHarness.measure(warmup = 2, iters = 3) {
            kernelEngine.firedCodesSorted(instance)
        }.medianMs
    }

    @Test
    fun groupScopedFillScalesSubQuadratically() {
        // IF137 paired estimator: interleaved N/4N, median of within-pair ratios (via the shared PerfHarness helper),
        // which also records and anchors both endpoints — the growth ratio alone cannot see a uniform slowdown.
        PerfHarness.assertSubQuadraticLane(
            label = "group-scope fill",
            small = N,
            identityAt = { n -> PerfHarness.measurementLabel(scenario, n) },
            culprit = "the per-row fill scope scan",
            clock = "wall",
            unit = "rows",
            batch = NOISE_BATCH,
        ) { medianMs(it, NOISE_BATCH) }
    }

    @Test
    fun groupScopedFillMatchesKernelComplexityClass() {
        val accepted = PerfHarness.assertCrossEngineClassHolds(
            label = "group-scope fill",
            ceiling = CROSS_ENGINE_CEILING,
        ) {
            // All four in ONE pass, so they share a load slice. Only the INTERPRETER arms are batched: this lane
            // measures ~9 ms at n=8000 with ±28.4 % run-to-run noise (see NOISE_BATCH) and a ratio of ratios
            // multiplies four such samples, while the kernel arms are several times dearer and already sit well
            // clear of the noisy band, so batching them would triple the lane's dominant cost for nothing.
            //
            // Hence `/ NOISE_BATCH`: `timeInterpreter(batch = b)` times the whole `repeat(b)` block and does NOT
            // normalize, so an unnormalized arm is b evaluations against the kernel's one. The BAR never saw this
            // — growth is a ratio of ratios and the constant cancels — but the printed line did, reporting the
            // interpreter 3x dearer than it is, which inverts the very comparison this test exists to publish.
            // Normalizing here rather than in `medianMs` is deliberate: the growth lane ANCHORS its values, so
            // changing what they mean would invalidate the committed block. This site anchors nothing.
            PerfHarness.RatioGrowth(
                iSmall = medianMs(N, NOISE_BATCH) / NOISE_BATCH,
                kSmall = kernelMedianMs(N),
                iLarge = medianMs(N * 4, NOISE_BATCH) / NOISE_BATCH,
                kLarge = kernelMedianMs(N * 4),
            )
        }
        // The PLAIN measurement label is right here, not the rebatched one the growth lane keys its anchor with:
        // these numbers are per-evaluation after the normalization above, which is exactly what that label names.
        println("[perf] %s | %s group-scope fill vs kernel: N=%d interp=%.1f kernel=%.1f (%.2f×) | N=%d interp=%.1f kernel=%.1f (%.2f×)"
            .format(Locale.ROOT, 
                PerfHarness.measurementLabel(scenario, N),
                PerfHarness.measurementLabel(scenario, N * 4),
                N, accepted.iSmall, accepted.kSmall, accepted.ratioSmall,
                N * 4, accepted.iLarge, accepted.kLarge, accepted.ratioLarge,
            ))
    }

    private companion object {
        /** See `PerfGateTest.NOISE_BATCH`: this lane measured 9.06 ms at n=8000 with ±28.4% run-to-run noise. */
        const val NOISE_BATCH = 3

        /** The ratio may not worsen by more than this between the two scales. Unchanged by the 2026-08-16
         *  instrument fix — what changed is that a breach is now re-measured, not that the bar moved. */
        const val CROSS_ENGINE_CEILING = 2.5

        const val N = 2000
        const val MAX = 200_000   // Items repeatability — FAR above 4×N, so the kernel evaluates group-fill at scale
        const val CN = "/Order/CustomerName"
        const val SKU = "/Order/Items/Sku"
    }
}
