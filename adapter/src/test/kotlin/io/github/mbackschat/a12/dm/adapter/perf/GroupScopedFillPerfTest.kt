package io.github.mbackschat.a12.dm.adapter.perf

import io.github.mbackschat.a12.dm.adapter.laws.RuntimeLaws
import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Perf gate — a group-SCOPED field-fill quantifier over a large repeatable group stays **linear** (IF49). The
 * scope `/Order/Items*` expands to every descendant field cell across all rows, so a naive tally could be
 * O(rows·fields); this asserts sub-quadratic growth (4× the rows ≪ 16× the time) AND that the interpreter stays
 * in the kernel's complexity class (the interp/kernel ratio must not grow with N). Mirrors `OuterCorrelationPerfTest`.
 */
@Tag("perf")
class GroupScopedFillPerfTest {

    private val order = RuntimeLaws.model("models/order.dm.json")
    private val m = BoundModel.ofJson(order)

    // FieldFilled(CustomerName) And AtLeastOneFieldFilled(/Order/Items*)  — the instantiated-range tally over all cells
    private val cond = DslRenderer().render(m.field(CN).filled().and(Dsl.atLeastOneFieldFilled(m.flattenGroup("/Order/Items*"))))

    private val fields = listOf(FieldDef(CN, Kind.STRING), FieldDef(SKU, Kind.STRING), FieldDef(COUNT, Kind.NUMBER, scale = 0))
    private val def = ModelDef(fields, listOf(RuleDef("/Order/R", "/Order", cond, "NF", "../CustomerName")),
        repeatableGroups = setOf("/Order/Items"), declaredReps = mapOf("/Order/Items" to 200_000), indexFields = setOf(SKU))

    private fun doc(n: Int): Document {
        val d = def.newDocument()
        d.field(CN, "Acme", 1, 1)
        for (r in 1..n) d.field(SKU, "S$r", 1, r, 1)
        return d
    }

    private fun minMs(n: Int): Double {
        val interp = DmInterpreter(def)
        val d = doc(n)
        repeat(2) { interp.validateFull(d) }
        return (1..3).minOf { val t = System.nanoTime(); interp.validateFull(d); (System.nanoTime() - t) / 1e6 }
    }

    private val kernelEngine = RuntimeLaws.kernelEngine(RuntimeLaws.withRule(order, "/Order", "R", "../CustomerName", "NF", cond))

    private fun kDoc(n: Int): RuntimeLaws.Doc {
        val d = RuntimeLaws.doc().group("/Order", 1).field(CN, "Acme", 1, 1)
        for (r in 1..n) d.group("/Order/Items", 1, r).field(SKU, "S$r", 1, r, 1)
        return d
    }

    private fun kernelMinMs(n: Int): Double {
        val instance = kernelEngine.instance(kDoc(n))
        repeat(2) { kernelEngine.firedCodesSorted(instance) }
        return (1..3).minOf { val t = System.nanoTime(); kernelEngine.firedCodesSorted(instance); (System.nanoTime() - t) / 1e6 }
    }

    @Test
    fun groupScopedFillScalesSubQuadratically() {
        // IF137 paired estimator: interleaved N/4N, median of within-pair ratios (via the shared PerfHarness helper).
        val g = PerfHarness.pairedGrowth(N, N * 4) { minMs(it) }
        println("[perf] group-scope fill: %.1fms@%d → %.1fms@%d  median-growth=%.1f× (linear≈4, quadratic≈16)"
            .format(g.smallMs, N, g.largeMs, N * 4, g.ratio))
        assertTrue(g.ratio < PerfBounds.SUB_QUADRATIC_MAX,
            "group-scope fill scaling looks quadratic: 4× the rows took ${g.ratio}× the time (linear≈4, quadratic≈16)")
    }

    @Test
    fun groupScopedFillMatchesKernelComplexityClass() {
        val (iSmall, kSmall) = minMs(N) to kernelMinMs(N)
        val (iLarge, kLarge) = minMs(N * 4) to kernelMinMs(N * 4)
        val ratioSmall = iSmall / kSmall; val ratioLarge = iLarge / kLarge
        println("[perf] group-scope fill vs kernel: N=%d interp=%.1f kernel=%.1f (%.2f×) | N=%d interp=%.1f kernel=%.1f (%.2f×)"
            .format(N, iSmall, kSmall, ratioSmall, N * 4, iLarge, kLarge, ratioLarge))
        assertTrue(ratioLarge < ratioSmall * 2.5,
            "interp/kernel ratio grew from $ratioSmall× to $ratioLarge× at 4× rows — interp is a worse class than the kernel")
    }

    private companion object {
        const val N = 2000
        const val CN = "/Order/CustomerName"
        const val SKU = "/Order/Items/Sku"
        const val COUNT = "/Order/Items/Count"
    }
}
