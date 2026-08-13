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

    private fun medianMs(n: Int): Double {
        return PerfHarness.timeInterpreter(scenario, n, warmup = 2, iters = 3).medianMs
    }

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
        ) { medianMs(it) }
    }

    @Test
    fun groupScopedFillMatchesKernelComplexityClass() {
        val (iSmall, kSmall) = medianMs(N) to kernelMedianMs(N)
        val (iLarge, kLarge) = medianMs(N * 4) to kernelMedianMs(N * 4)
        val ratioSmall = iSmall / kSmall; val ratioLarge = iLarge / kLarge
        println("[perf] %s | %s group-scope fill vs kernel: N=%d interp=%.1f kernel=%.1f (%.2f×) | N=%d interp=%.1f kernel=%.1f (%.2f×)"
            .format(Locale.ROOT, 
                PerfHarness.measurementLabel(scenario, N),
                PerfHarness.measurementLabel(scenario, N * 4),
                N, iSmall, kSmall, ratioSmall, N * 4, iLarge, kLarge, ratioLarge,
            ))
        assertTrue(ratioLarge < ratioSmall * 2.5,
            "interp/kernel ratio grew from $ratioSmall× to $ratioLarge× at 4× rows — interp is a worse class than the kernel")
    }

    private companion object {
        const val N = 2000
        const val MAX = 200_000   // Items repeatability — FAR above 4×N, so the kernel evaluates group-fill at scale
        const val CN = "/Order/CustomerName"
        const val SKU = "/Order/Items/Sku"
    }
}
