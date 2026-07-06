package io.github.mbackschat.a12.dm.adapter.perf

import io.github.mbackschat.a12.dm.adapter.laws.RuntimeLaws
import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.Document
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
 * Perf gate — the `$` OUTER-CORRELATION (IG23) stays **linear PER ROW**. A self-correlation is O(N²) *by
 * nature* (each row is compared against every other — the kernel is O(N²) here too); the risk this guards is an
 * accidental O(N³) from per-inner-row O(N) work. So it asserts **sub-cubic** growth: 4× the rows must cost well
 * under 64× the time (a clean quadratic ≈ 16×). Passing proves the per-row filter work is O(1) — the `$` ref
 * resolves against the fixed enclosing row ([ctxOf]) and `rowContexts` reuses the memoized prefix index.
 * Robust estimator (min-of-repeats, small+large back-to-back) mirrors `PerfGateTest.scalingIsSubQuadratic`.
 */
@Tag("perf")
class OuterCorrelationPerfTest {

    private val order = RuntimeLaws.model("models/order.dm.json")
    private val m = BoundModel.ofJson(order)

    // FieldFilled(Count) And AtLeastOneFieldFilled(Items* / Count Having
    //   CurrentRepetition(Items) != CurrentRepetition($Items) And [Count] == [$Count])
    private val corr = DslRenderer().render(m.field(COUNT).filled().and(
        Dsl.atLeastOneFieldFilled(m.flatten("/Order/Items*/Count").having(
            Dsl.currentRepetition(m.group("/Order/Items"))
                .ne(Dsl.currentRepetition(m.group("/Order/Items"), true))
                .and(m.num(COUNT).eq(m.num(COUNT).outer()))))))

    private val fields = listOf(
        FieldDef(CN, Kind.STRING), FieldDef(SKU, Kind.STRING), FieldDef(COUNT, Kind.NUMBER, scale = 0))
    private val def = ModelDef(fields, listOf(RuleDef("/Order/Corr", "/Order", corr, "CORR", "../Items/Count")),
        repeatableGroups = setOf("/Order/Items"), declaredReps = mapOf("/Order/Items" to 200_000), indexFields = setOf(SKU))

    private fun doc(n: Int): Document {
        val d = Document(def.kinds, def.declaredReps, def.unsignedFields, def.rangeConfig, def.dateFormats, def.scales)
        for (r in 1..n) { d.field(SKU, "S$r", 1, r, 1); d.field(COUNT, (r % 50).toString(), 1, r, 1) }
        return d
    }

    private fun minMs(n: Int): Double {
        val interp = DmInterpreter(def)
        val d = doc(n)
        repeat(2) { interp.validateFull(d) }                    // warmup (JIT + lazy index)
        return (1..3).minOf { val t = System.nanoTime(); interp.validateFull(d); (System.nanoTime() - t) / 1e6 }
    }

    private val kernelEngine = RuntimeLaws.kernelEngine(
        RuntimeLaws.withRule(order, "/Order", "Corr", "../Items/Count", "CORR", corr))   // Groovy codegen once

    private fun kDoc(n: Int): RuntimeLaws.Doc {
        val d = RuntimeLaws.doc().group("/Order", 1).field(CN, "Acme", 1, 1)
        for (r in 1..n) d.group("/Order/Items", 1, r).field(SKU, "S$r", 1, r, 1).field(COUNT, (r % 50).toString(), 1, r, 1)
        return d
    }

    private fun kernelMinMs(n: Int): Double {
        val instance = kernelEngine.instance(kDoc(n))           // build the IDocument once, outside timing
        repeat(2) { kernelEngine.firedCodesSorted(instance) }   // warmup — the timed region is steady-state validate
        return (1..3).minOf { val t = System.nanoTime(); kernelEngine.firedCodesSorted(instance); (System.nanoTime() - t) / 1e6 }
    }

    @Test
    fun outerCorrelationScalesSubCubically() {
        var best = Double.MAX_VALUE
        var small = 0.0
        var large = 0.0
        repeat(3) {
            val s = minMs(N)
            val l = minMs(N * 4)
            val growth = l / s
            if (growth < best) { best = growth; small = s; large = l }
        }
        println("[perf] \$ correlation: %.1fms@%d → %.1fms@%d  min-growth=%.1f× (quadratic≈16, cubic≈64)"
            .format(small, N, large, N * 4, best))
        assertTrue(best < 36.0,
            "\$ correlation scaling looks cubic: 4× the rows took ${best}× the time (quadratic≈16, cubic≈64)")
    }

    /**
     * **Measured against the kernel** (INTERPRETER-PERFORMANCE "measure AGAINST the kernel"): the interpreter
     * must stay in the kernel's O(N²) complexity CLASS for a `$` self-correlation — not merely sub-cubic in
     * isolation. Times BOTH engines at N and 4N and asserts the interp/kernel RATIO is stable (a worse class
     * would make the ratio grow ~4× with N). Being a constant factor slower than the kernel is fine (perf goal
     * #2 is "equal-or-better"; #1 is just "good = same class"); a growing ratio is the regression this catches.
     */
    @Test
    fun outerCorrelationMatchesKernelComplexityClass() {
        val (iSmall, kSmall) = minMs(N) to kernelMinMs(N)
        val (iLarge, kLarge) = minMs(N * 4) to kernelMinMs(N * 4)
        val ratioSmall = iSmall / kSmall
        val ratioLarge = iLarge / kLarge
        println("[perf] \$ correlation vs kernel: N=%d interp=%.1f kernel=%.1f (%.2f×) | N=%d interp=%.1f kernel=%.1f (%.2f×)"
            .format(N, iSmall, kSmall, ratioSmall, N * 4, iLarge, kLarge, ratioLarge))
        // The ratio must not GROW with N (that would mean a worse complexity class than the kernel). Generous
        // 2.5× headroom for timing noise; a true O(N³)-vs-O(N²) regression would blow the ratio up ~4×.
        assertTrue(ratioLarge < ratioSmall * 2.5,
            "interp/kernel ratio grew from $ratioSmall× to $ratioLarge× at 4× rows — interp is a worse class than the kernel")
    }

    private companion object {
        const val N = 400
        const val CN = "/Order/CustomerName"
        const val SKU = "/Order/Items/Sku"
        const val COUNT = "/Order/Items/Count"
    }
}
