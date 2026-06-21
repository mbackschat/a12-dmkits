package io.github.mbackschat.a12.dm.interpreter.spike

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Runs the SAME commonMain interpreter on EVERY KMP target (JVM + JS via Node) with hand-asserted
 * expectations — no kernel here (that oracle is JVM-only; the JVM kernel-parity proof lives in the
 * :adapter differential tests). This is the cross-platform thesis made concrete: one engine, identical
 * results on both platforms. The expected values are the ones the kernel produced in :adapter.
 */
class InterpreterMultiplatformTest {

    private val q = "/Order/Quantity"
    private val es = "/Order/ExpressShipping"
    private val cn = "/Order/CustomerName"
    private val ic = "/Order/Items/Count"

    private val kinds = mapOf(
        q to Kind.NUMBER, es to Kind.BOOLEAN, cn to Kind.STRING, ic to Kind.NUMBER,
    )

    private fun fires(values: Map<String, String>, cond: Cond) =
        Interpreter(SpikeDoc("/Order", values, kinds)).fires(cond)

    @Test
    fun comparisonAndThreeValuedLogic() {
        val cond = Cond.Cmp(CmpOp.LT, Expr.Field(q), Expr.NumLit(dec(100)))
        assertTrue(fires(mapOf(q to "50"), cond))
        assertFalse(fires(mapOf(q to "200"), cond))
    }

    @Test
    fun emptyValueTypeAsymmetry() {
        val numLt = Cond.Cmp(CmpOp.LT, Expr.Field(q), Expr.NumLit(dec(100)))
        assertTrue(fires(mapOf(cn to "Acme"), numLt), "empty number → 0 < 100 fires")
        assertFalse(fires(emptyMap(), numLt), "entirely empty row → not evaluated")

        val boolEq = Cond.Cmp(CmpOp.EQ, Expr.Field(es), Expr.BoolLit(true))
        assertFalse(fires(mapOf(cn to "Acme"), boolEq), "empty boolean → not evaluated")
        assertTrue(fires(mapOf(cn to "Acme", es to "true"), boolEq))
    }

    @Test
    fun fieldFilledGuardSuppressesEmptyZero() {
        val cond = Cond.And(
            Cond.Pred("FieldFilled", listOf(Expr.Field(q))),
            Cond.Cmp(CmpOp.LT, Expr.Field(q), Expr.NumLit(dec(100))),
        )
        assertFalse(fires(mapOf(cn to "Acme"), cond), "guard suppresses the empty-0 false positive")
        assertTrue(fires(mapOf(q to "50"), cond))
    }

    @Test
    fun starredSumOverRepetitionRows() {
        val rep = RepDoc(
            "/Order", "/Order/Items", mapOf(cn to "Acme"),
            listOf(mapOf(ic to "40"), emptyMap(), mapOf(ic to "40")), kinds,
        )
        val sum80 = Cond.And(
            Cond.Pred("FieldFilled", listOf(Expr.Field(cn))),
            Cond.Cmp(CmpOp.EQ, Expr.Func("Sum", listOf(Expr.Star(ic))), Expr.NumLit(dec(80))),
        )
        assertTrue(Interpreter(rep.outerContext()).fires(sum80), "Sum(40, ∅→0, 40) == 80")
    }

    @Test
    fun perRowIterationFiresOnOffendingRows() {
        val rep = RepDoc(
            "/Order", "/Order/Items", mapOf(cn to "Acme"),
            listOf(mapOf(ic to "10"), mapOf(ic to "80"), mapOf(ic to "90")), kinds,
        )
        val perRow = Cond.And(
            Cond.Pred("FieldFilled", listOf(Expr.Field(ic))),
            Cond.Cmp(CmpOp.GT, Expr.Field(ic), Expr.NumLit(dec(50))),
        )
        val firing = rep.rowContexts().mapIndexedNotNull { i, ctx ->
            if (Interpreter(ctx).fires(perRow)) i + 1 else null
        }
        assertEquals(listOf(2, 3), firing)
    }
}
