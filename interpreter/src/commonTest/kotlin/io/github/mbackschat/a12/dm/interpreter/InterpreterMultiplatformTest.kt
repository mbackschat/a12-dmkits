package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.ast.CmpOp
import io.github.mbackschat.a12.dm.interpreter.ast.Cond
import io.github.mbackschat.a12.dm.interpreter.ast.Expr
import io.github.mbackschat.a12.dm.interpreter.eval.EvalConfig
import io.github.mbackschat.a12.dm.interpreter.eval.Interpreter
import io.github.mbackschat.a12.dm.interpreter.eval.RepDoc
import io.github.mbackschat.a12.dm.interpreter.eval.SingleRowDoc
import io.github.mbackschat.a12.dm.interpreter.eval.renderMessage
import io.github.mbackschat.a12.dm.interpreter.model.EvalClock
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.MsgType
import io.github.mbackschat.a12.dm.interpreter.parse.parseCondition

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
        Interpreter(SingleRowDoc("/Order", values, kinds)).fires(cond)

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
        val doc = SingleRowDoc("/Order", mapOf(q to "10"), kinds)
        assertEquals(MsgType.OMISSION, Interpreter(doc).firedType(cond))
    }

    @Test
    fun parserProducesEvaluableAstOnBothPlatforms() {
        // real condition text → AST → interpret, cross-platform (no kernel here)
        val guarded = parseCondition("FieldFilled(Quantity) And [Quantity] < 100", "/Order")
        assertTrue(Interpreter(SingleRowDoc("/Order", mapOf(q to "50"), kinds)).fires(guarded))
        assertFalse(Interpreter(SingleRowDoc("/Order", mapOf(cn to "Acme"), kinds)).fires(guarded), "guard suppresses empty")

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
        println("[interpreter] interpreted $n rows (per-row + Sum aggregate) in $elapsed")
        // Regression guard: ~30ms actual on the JVM; a gross O(n^2)-type regression would blow past this.
        // Generous headroom so JS (Node, interpreted) and CI variance don't flake. A real perf-regression
        // harness (JMH / tracked baselines) is Phase-1 work — this just trips on catastrophic slowdowns.
        assertTrue(elapsed < 5.seconds, "throughput regression: $n rows took $elapsed (expected well under 5s)")
    }

    @Test
    fun todayConstantResolvesToInjectedClock() {
        // The exact-value boundary the (wall-clock-independent) :adapter differential can't pin: `Today`
        // resolves to the INJECTED clock date and compares chronologically. Deterministic — no kernel.
        val od = "/Order/OrderDate"
        val dateKinds = mapOf(od to Kind.DATE)
        val clock = EvalClock(today = "2025-06-23")
        fun fires(date: String, cond: String) =
            Interpreter(SingleRowDoc("/Order", mapOf(od to date), dateKinds), EvalConfig(clock = clock))
                .fires(parseCondition(cond, "/Order"))
        assertTrue(fires("2025-06-23", "[OrderDate] == Today"), "an order dated today equals Today")
        assertFalse(fires("2025-06-22", "[OrderDate] == Today"), "the day before does not equal Today")
        assertTrue(fires("2025-06-22", "[OrderDate] < Today"), "the day before is before Today")
        assertFalse(fires("2025-06-24", "[OrderDate] < Today"), "the day after is not before Today")
    }

    @Test
    fun nowConstantResolvesToInjectedClock() {
        // The date-time twin of the Today boundary: `Now` resolves to the INJECTED clock date-time and
        // compares chronologically against a DATE_TIME field. Deterministic — the :adapter differential can
        // only pin the FAR cases (wall-clock vs injected); this pins the exact instant. No kernel.
        val sa = "/Order/ScheduledAt"
        val dtKinds = mapOf(sa to Kind.DATE_TIME)
        val clock = EvalClock(today = "2025-06-23", now = "2025-06-23T12:00:00")
        fun fires(instant: String, cond: String) =
            Interpreter(SingleRowDoc("/Order", mapOf(sa to instant), dtKinds), EvalConfig(clock = clock))
                .fires(parseCondition(cond, "/Order"))
        assertTrue(fires("2025-06-23T12:00:00", "[ScheduledAt] == Now"), "the same instant equals Now")
        assertFalse(fires("2025-06-23T11:59:59", "[ScheduledAt] == Now"), "a second earlier does not equal Now")
        assertTrue(fires("2025-06-23T11:59:59", "[ScheduledAt] < Now"), "a second earlier is before Now")
        assertFalse(fires("2025-06-23T12:00:01", "[ScheduledAt] < Now"), "a second later is not before Now")
    }

    @Test
    fun rendersMessagePlaceholderOnBothPlatforms() {
        val ctx = SingleRowDoc("/Order", mapOf(q to "50"), kinds)
        assertEquals("low: 50", renderMessage("low: \$Quantity.value\$", "/Order", ctx))
    }
}
