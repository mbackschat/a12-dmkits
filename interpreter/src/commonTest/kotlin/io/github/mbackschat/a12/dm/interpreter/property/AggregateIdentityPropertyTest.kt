package io.github.mbackschat.a12.dm.interpreter.property

import io.github.mbackschat.a12.dm.interpreter.DEC_ZERO
import io.github.mbackschat.a12.dm.interpreter.dec
import io.github.mbackschat.a12.dm.interpreter.ast.Val
import io.github.mbackschat.a12.dm.interpreter.eval.Aggregates
import io.github.mbackschat.a12.dm.interpreter.eval.EvalContext
import io.github.mbackschat.a12.dm.interpreter.eval.EvalConfig
import io.github.mbackschat.a12.dm.interpreter.eval.Interpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.parse.parseExpression
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * **Property 5 — Aggregate identities** (proposal §8). Two layers:
 *
 * 1. the pure [Aggregates] folds over **sampled** value lists — `Sum` folds, `MaxValue`/`MinValue` are the
 *    extremes, `NumberOfDifferentValues` counts distinct (scale-insensitively), and the **empty list folds to 0**
 *    for all four (the kernel's empty-aggregate identity);
 * 2. the strategy's empty handling through the real engine — the **operand-list** `Max`/`Min` SUBSTITUTE an empty
 *    operand as `0` (so `Max(-5, ∅) == 0`), while the **starred** `MaxValue`/`MinValue` **skip** empties (so
 *    `MaxValue(-5, ∅) == -5`) and an all-empty star is `0`. Kernel-free; runs on JVM + JS.
 */
class AggregateIdentityPropertyTest {

    private val zero = Val.Num(DEC_ZERO)

    @Test
    fun emptyAggregatesAreZero() {
        assertEquals(zero, Aggregates.sum(emptyList()))
        assertEquals(zero, Aggregates.max(emptyList()))
        assertEquals(zero, Aggregates.min(emptyList()))
        assertEquals(zero, Aggregates.numberOfDifferentValues(emptyList()))
    }

    @Test
    fun sumMaxMinMatchReferenceOverSampledNumberLists() {
        for (seed in PropertySupport.SEEDS) {
            val rnd = Random(seed)
            val nums = List(rnd.nextInt(0, 8)) { Val.Num(dec(rnd.nextInt(-50, 50))) }
            val decs = nums.map { (it as Val.Num).v }
            assertEquals(Val.Num(decs.fold(DEC_ZERO) { a, v -> a + v }), Aggregates.sum(nums), "sum seed=$seed")
            assertEquals(if (decs.isEmpty()) zero else Val.Num(decs.maxOrNull()!!), Aggregates.max(nums), "max seed=$seed")
            assertEquals(if (decs.isEmpty()) zero else Val.Num(decs.minOrNull()!!), Aggregates.min(nums), "min seed=$seed")
        }
    }

    @Test
    fun numberOfDifferentValuesCountsDistinctByTypedKey() {
        // scale-insensitive numbers (49.90 ≡ 49.9), and type-separated (the number 1 ≠ the string "1").
        for (seed in PropertySupport.SEEDS) {
            val rnd = Random(seed)
            val pool: List<Val> = listOf(
                Val.Num(dec("49.90")), Val.Num(dec("49.9")), Val.Num(dec("50")),
                Val.Txt("a"), Val.Txt("b"), Val.Bool(true), Val.Bool(false),
            )
            val sample = List(rnd.nextInt(0, 10)) { pool[rnd.nextInt(pool.size)] }
            val expected = sample.map { key(it) }.toSet().size
            assertEquals(Val.Num(dec(expected)), Aggregates.numberOfDifferentValues(sample), "distinct seed=$seed sample=$sample")
        }
    }

    @Test
    fun operandListSubstitutesEmptyWhileStarSkipsIt() {
        // operand-list Max/Min: empty operand substitutes 0 → Max(-5, ∅)=0, Min(-5, ∅)=-5.
        val flat = PropertySupport.flat.model
        val flatDoc = flat.newDocument().field("/T/Qty", "-5", 1, 1)   // Price left empty
        val fctx = flatDoc.contextsFor("/T").single()
        assertEquals(Val.Num(dec(0)), evalExpr(flat, fctx, "Max(Qty, Price)", "/T"), "operand-list Max substitutes empty as 0")
        assertEquals(Val.Num(dec(-5)), evalExpr(flat, fctx, "Min(Qty, Price)", "/T"), "operand-list Min substitutes empty as 0")

        // starred MaxValue/MinValue: empties are SKIPPED → over rows [-5, ∅] the max/min is -5.
        val cart = PropertySupport.singleRep.model
        val cartDoc = cart.newDocument()
            .field("/Cart/Lines/Amount", "-5.00", 1, 1, 1)
            .group("/Cart/Lines", 1, 2)                          // an instantiated row with an EMPTY Amount
        val cctx = cartDoc.contextsFor("/Cart").single()
        assertEquals(Val.Num(dec("-5")), evalExpr(cart, cctx, "MaxValue(Lines*/Amount)", "/Cart"), "starred MaxValue skips empty")
        assertEquals(Val.Num(dec("-5")), evalExpr(cart, cctx, "MinValue(Lines*/Amount)", "/Cart"), "starred MinValue skips empty")

        // an all-empty star is the empty-aggregate identity 0.
        val emptyCart = cart.newDocument().group("/Cart", 1).group("/Cart/Lines", 1, 1)
        val ectx = emptyCart.contextsFor("/Cart").single()
        assertEquals(zero, evalExpr(cart, ectx, "MaxValue(Lines*/Amount)", "/Cart"), "all-empty starred MaxValue is 0")
    }

    private fun evalExpr(model: ModelDef, ctx: EvalContext, text: String, base: String): Val =
        Interpreter(ctx, EvalConfig(indexFields = model.indexFields)).evalExpression(parseExpression(text, base))

    /** The distinct key mirroring [Aggregates.numberOfDifferentValues] (typed, scale-insensitive numbers). */
    private fun key(v: Val): String = when (v) {
        is Val.Num -> "N" + v.v.scale19().display()
        is Val.Txt -> "S" + v.v
        is Val.Bool -> "B" + v.v
        else -> "?"
    }
}
