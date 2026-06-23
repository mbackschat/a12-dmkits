package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.ast.CmpOp
import io.github.mbackschat.a12.dm.interpreter.ast.Cond
import io.github.mbackschat.a12.dm.interpreter.ast.Expr
import io.github.mbackschat.a12.dm.interpreter.eval.Interpreter
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.eval.RepDoc
import io.github.mbackschat.a12.dm.interpreter.dec
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.function.Executable

/**
 * Differential — nested repetition (`*`-iteration) over /Order/Items, differential vs the REAL kernel:
 *  - per-row rules iterate once per row and fire on the offending rows (compared as the set of rows);
 *  - starred aggregates (Sum / MaxValue / NumberOfDifferentValues) collapse the rows — Sum substitutes
 *    0 / Max ignores empties — and the date aggregate compares chronologically.
 */
class RepetitionDiffTest {

    private val order = RuntimeLaws.model("models/order.dm.json")

    @Test
    fun perRowRuleFiresOnTheOffendingRows() {
        val model = RuntimeLaws.withRule(order, "/Order", "CountCap", "../Items/Count", "COUNT_HIGH",
            "FieldFilled(Items/Count) And [Items/Count] > 50")
        val ast = Cond.And(
            Cond.Pred("FieldFilled", listOf(Expr.Field(IC))),
            Cond.Cmp(CmpOp.GT, Expr.Field(IC), Expr.NumLit(dec(50))),
        )
        assertAll(
            rowDiff("10/80/90", model, "COUNT_HIGH", ast, listOf("10", "80", "90")),
            rowDiff("10/20/30", model, "COUNT_HIGH", ast, listOf("10", "20", "30")),
            rowDiff("60/_/70", model, "COUNT_HIGH", ast, listOf("60", null, "70")),
        )
    }

    @Test
    fun starredNumberAggregates() {
        assertAll(
            aggDiff("SUM80A", "Sum(Items*/Count) == 80", sumEq(80), listOf("40", null, "40")),
            aggDiff("SUM120", "Sum(Items*/Count) == 80", sumEq(80), listOf("40", "40", "40")),
            aggDiff("SUM0", "Sum(Items*/Count) == 0", sumEq(0), listOf(null, null, null)),
            aggDiff("MAXV40", "MaxValue(Items*/Count) == 40", maxvalEq(40), listOf("40", null, "10")),
            aggDiff("NDV1", "NumberOfDifferentValues(Items*/Count) == 1", ndvEq(1), listOf("5", null, "5")),
            aggDiff("NDV2", "NumberOfDifferentValues(Items*/Count) == 1", ndvEq(1), listOf("5", "6", "5")),
        )
    }

    @Test
    fun starredDateAggregate() {
        val cond = "MaxValue(Items*/PromiseDate) > [DeliveryDate]"
        val ast = Cond.Cmp(CmpOp.GT, Expr.Func("MaxValue", listOf(Expr.Star(IP))), Expr.Field(DD))
        assertAll(
            dateDiff("LATE1", cond, ast, listOf("2026-06-15", "2026-07-20", "2026-06-20")),
            dateDiff("LATE2", cond, ast, listOf("2026-06-15", "2026-06-20", "2026-06-25")),
        )
    }

    // ---- differential helpers (kernel vs interpreter over the SAME instance) ----

    private fun rowDiff(name: String, model: String, code: String, ast: Cond, counts: List<String?>): Executable {
        val kernel = kernelFiringRows(model, code, counts)
        val interp = repItems(counts).rowContexts()
            .mapIndexedNotNull { i, ctx -> if (Interpreter(ctx).fires(ast)) i + 1 else null }.toSet()
        return Executable { assertEquals(kernel, interp, "per-row $name [kernel=$kernel interp=$interp]") }
    }

    private fun aggDiff(code: String, cond: String, ast: Cond, counts: List<String?>): Executable {
        val model = RuntimeLaws.withRule(order, "/Order", code, "../CustomerName", code,
            "FieldFilled(CustomerName) And $cond")
        val kernel = RuntimeLaws.fires(model, code, kernelItems(counts))
        val interp = Interpreter(repItems(counts).outerContext()).fires(Cond.And(FILLED_CN, ast))
        return Executable { assertEquals(kernel, interp, "$cond over $counts [kernel=$kernel interp=$interp]") }
    }

    private fun dateDiff(code: String, cond: String, ast: Cond, promises: List<String?>): Executable {
        val model = RuntimeLaws.withRule(order, "/Order", code, "../CustomerName", code,
            "FieldFilled(CustomerName) And $cond")
        val kernel = RuntimeLaws.fires(model, code, kernelDateRows(promises))
        val interp = Interpreter(repDateRows(promises).outerContext()).fires(Cond.And(FILLED_CN, ast))
        return Executable { assertEquals(kernel, interp, "$cond over $promises [kernel=$kernel interp=$interp]") }
    }

    private fun kernelFiringRows(model: String, code: String, counts: List<String?>): Set<Int> {
        val rx = Regex("""\[1, (\d+), 1]""")
        return RuntimeLaws.messages(model, kernelItems(counts)).filter { it.code() == code }
            .mapNotNull { rx.find(it.errorField())?.groupValues?.get(1)?.toInt() }.toSet()
    }

    // ---- doc builders (kernel Doc + RepDoc kept consistent) ----

    private fun kernelItems(counts: List<String?>): RuntimeLaws.Doc {
        val d = RuntimeLaws.doc().group("/Order", 1).field(CN, "Acme", 1, 1)
        counts.forEachIndexed { i, c ->
            val r = i + 1
            d.group("/Order/Items", 1, r).field(IS, ('A' + i).toString(), 1, r, 1)
            if (c != null) d.field(IC, c, 1, r, 1) else d.emptyField(IC, 1, r, 1)
        }
        return d
    }

    private fun repItems(counts: List<String?>): RepDoc {
        val rows = counts.mapIndexed { i, c ->
            buildMap {
                put(IS, ('A' + i).toString())
                if (c != null) put(IC, c)
            }
        }
        return RepDoc("/Order", "/Order/Items", mapOf(CN to "Acme"), rows, KINDS)
    }

    private fun kernelDateRows(promises: List<String?>): RuntimeLaws.Doc {
        val d = RuntimeLaws.doc().group("/Order", 1).field(CN, "Acme", 1, 1).field(DD, "2026-07-01", 1, 1)
        promises.forEachIndexed { i, p ->
            val r = i + 1
            d.group("/Order/Items", 1, r).field(IS, "S$r", 1, r, 1)
            if (p != null) d.field(IP, p, 1, r, 1) else d.emptyField(IP, 1, r, 1)
        }
        return d
    }

    private fun repDateRows(promises: List<String?>): RepDoc {
        val rows = promises.mapIndexed { i, p ->
            buildMap {
                put(IS, "S${i + 1}")
                if (p != null) put(IP, p)
            }
        }
        return RepDoc("/Order", "/Order/Items", mapOf(CN to "Acme", DD to "2026-07-01"), rows, KINDS)
    }

    private companion object {
        const val CN = "/Order/CustomerName"
        const val DD = "/Order/DeliveryDate"
        const val IS = "/Order/Items/Sku"
        const val IC = "/Order/Items/Count"
        const val IP = "/Order/Items/PromiseDate"

        val KINDS = mapOf(
            CN to Kind.STRING, DD to Kind.DATE,
            IS to Kind.STRING, IC to Kind.NUMBER, IP to Kind.DATE,
        )
        val FILLED_CN = Cond.Pred("FieldFilled", listOf(Expr.Field(CN)))

        fun sumEq(n: Int) = Cond.Cmp(CmpOp.EQ, Expr.Func("Sum", listOf(Expr.Star(IC))), Expr.NumLit(dec(n)))
        fun maxvalEq(n: Int) =
            Cond.Cmp(CmpOp.EQ, Expr.Func("MaxValue", listOf(Expr.Star(IC))), Expr.NumLit(dec(n)))
        fun ndvEq(n: Int) =
            Cond.Cmp(CmpOp.EQ, Expr.Func("NumberOfDifferentValues", listOf(Expr.Star(IC))), Expr.NumLit(dec(n)))
    }
}
