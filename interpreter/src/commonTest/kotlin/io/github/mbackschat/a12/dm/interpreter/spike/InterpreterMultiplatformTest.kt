package io.github.mbackschat.a12.dm.interpreter.spike

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime

/**
 * Runs the SAME commonMain interpreter on EVERY KMP target (JVM + JS via Node) with hand-asserted
 * expectations — no kernel here (that oracle is JVM-only; the JVM kernel-parity proof lives in the
 * :adapter differential tests). This is the cross-platform thesis made concrete: one engine, identical
 * results on both platforms. The expected values are the ones the kernel produced in :adapter.
 */
class InterpreterMultiplatformTest {

    private val q = "/Order/Quantity"
    private val bq = "/Order/BackorderQuantity"
    private val es = "/Order/ExpressShipping"
    private val cn = "/Order/CustomerName"
    private val ic = "/Order/Items/Count"

    private val kinds = mapOf(
        q to Kind.NUMBER, bq to Kind.NUMBER, es to Kind.BOOLEAN, cn to Kind.STRING, ic to Kind.NUMBER,
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

    @Test
    fun arbitraryPrecisionDecimalOnBothPlatforms() {
        // 0.1 + 0.2 == 0.3 EXACTLY — holds with BigDecimal (JVM) and big.js (JS); a Double-backed JS
        // actual would fail here (0.1 + 0.2 = 0.30000000000000004). Proves the JS decimal is real.
        val rep = RepDoc(
            "/Order", "/Order/Items", mapOf(cn to "Acme"),
            listOf(mapOf(ic to "0.1"), mapOf(ic to "0.2")), kinds,
        )
        val cond = Cond.And(
            Cond.Pred("FieldFilled", listOf(Expr.Field(cn))),
            Cond.Cmp(CmpOp.EQ, Expr.Func("Sum", listOf(Expr.Star(ic))), Expr.NumLit(dec("0.3"))),
        )
        assertTrue(Interpreter(rep.outerContext()).fires(cond), "0.1 + 0.2 == 0.3 with no binary-float drift")
    }

    @Test
    fun polarityTypingRunsOnBothPlatforms() {
        // And: a filled VALUE branch + an empty-substituted OMISSION branch → OMISSION (omission wins)
        val cond = Cond.And(
            Cond.Cmp(CmpOp.GT, Expr.Field(q), Expr.NumLit(dec(5))),
            Cond.Cmp(CmpOp.GE, Expr.Field(bq), Expr.NumLit(dec(0))),
        )
        val doc = SpikeDoc("/Order", mapOf(q to "10"), kinds)
        assertEquals(MsgType.OMISSION, Interpreter(doc).firedType(cond))
    }

    @Test
    fun parserProducesEvaluableAstOnBothPlatforms() {
        // real condition text → AST → interpret, cross-platform (no kernel here)
        val guarded = parseCondition("FieldFilled(Quantity) And [Quantity] < 100", "/Order")
        assertTrue(Interpreter(SpikeDoc("/Order", mapOf(q to "50"), kinds)).fires(guarded))
        assertFalse(Interpreter(SpikeDoc("/Order", mapOf(cn to "Acme"), kinds)).fires(guarded), "guard suppresses empty")

        val agg = parseCondition("Sum(Items*/Count) == 80", "/Order")
        val rep = RepDoc(
            "/Order", "/Order/Items", mapOf(cn to "Acme"),
            listOf(mapOf(ic to "40"), mapOf(ic to "40")), kinds,
        )
        assertTrue(Interpreter(rep.outerContext()).fires(agg), "Sum(40, 40) == 80 from parsed text")
    }

    @Test
    fun scalesToManyRepetitionRows() {
        // Throughput sanity: a per-row rule over N rows + a starred aggregate. Correctness at scale +
        // a printed timing (no hard bound — CI-variance-free). Runs on JVM and JS (Node).
        val n = 20_000
        val rows = (1..n).map { mapOf(ic to (it % 100).toString()) }
        val rep = RepDoc("/Order", "/Order/Items", mapOf(cn to "Acme"), rows, kinds)
        val perRow = Cond.Cmp(CmpOp.GT, Expr.Field(ic), Expr.NumLit(dec(50)))
        val sumNonNeg = Cond.Cmp(CmpOp.GE, Expr.Func("Sum", listOf(Expr.Star(ic))), Expr.NumLit(dec(0)))

        var firing = 0
        var sumFires = false
        val elapsed = measureTime {
            firing = rep.rowContexts().count { Interpreter(it).fires(perRow) }
            sumFires = Interpreter(rep.outerContext()).fires(sumNonNeg)
        }
        assertEquals(n / 100 * 49, firing, "rows with Count in 51..99 fire (49 per 100)")
        assertTrue(sumFires)
        println("[spike] interpreted $n rows (per-row + Sum aggregate) in $elapsed")
        // Regression guard: ~30ms actual on the JVM; a gross O(n^2)-type regression would blow past this.
        // Generous headroom so JS (Node, interpreted) and CI variance don't flake. A real perf-regression
        // harness (JMH / tracked baselines) is Phase-1 work — this just trips on catastrophic slowdowns.
        assertTrue(elapsed < 5.seconds, "throughput regression: $n rows took $elapsed (expected well under 5s)")
    }
}
