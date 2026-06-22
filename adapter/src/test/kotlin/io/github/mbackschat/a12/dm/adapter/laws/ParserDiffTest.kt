package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.Interpreter
import io.github.mbackschat.a12.dm.interpreter.Kind
import io.github.mbackschat.a12.dm.interpreter.RepDoc
import io.github.mbackschat.a12.dm.interpreter.SingleRowDoc
import io.github.mbackschat.a12.dm.interpreter.parseCondition
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.function.Executable

/**
 * Differential — a REAL parser end to end: the interpreter is driven from condition TEXT (parsed to
 * the AST) instead of a hand-built AST, and still agrees with the kernel. Differential vs RuntimeLaws
 * across single-row, starred-aggregate, and per-row shapes.
 */
class ParserDiffTest {

    private val order = RuntimeLaws.model("models/order.dm.json")
    private val kinds = mapOf(
        Q to Kind.NUMBER, BQ to Kind.NUMBER, TC to Kind.CONFIRM, PC to Kind.STRING,
        CN to Kind.STRING, IC to Kind.NUMBER, IS to Kind.STRING,
    )

    private data class Case(
        val text: String,
        val errorRel: String,
        val empties: Set<String> = emptySet(),
        val values: Map<String, String> = emptyMap(),
    )

    private val singleRow = listOf(
        Case("[Quantity] < 100", "../Quantity", values = mapOf(Q to "50")),
        Case("[Quantity] < 100", "../Quantity", values = mapOf(Q to "200")),
        Case("[Quantity] < 100", "../Quantity", values = mapOf(CN to "Acme")),                       // empty→0 fires
        Case("[TermsConfirmed] != True", "../TermsConfirmed", empties = setOf(TC), values = mapOf(CN to "Acme")),
        Case("[ProductCode] == \"ABC\"", "../ProductCode", values = mapOf(CN to "Acme", PC to "ABC")),
        Case("FieldFilled(Quantity) And [Quantity] < 100", "../Quantity", values = mapOf(CN to "Acme")), // guarded
        Case("Min([Quantity], [BackorderQuantity]) == 0", "../Quantity",
            empties = setOf(Q), values = mapOf(CN to "Acme", BQ to "5")),
        Case("[Quantity] > 5 Or [BackorderQuantity] >= 0", "../Quantity",
            empties = setOf(BQ), values = mapOf(Q to "10")),
    )

    @Test
    fun parsedSingleRowConditionsAgreeWithKernel() {
        val checks = singleRow.mapIndexed { i, c ->
            val code = "PARSE_$i"
            val model = RuntimeLaws.withRule(order, "/Order", "R$i", c.errorRel, code, c.text)
            val kernel = RuntimeLaws.fires(model, code, setOf("/Order"), c.empties, c.values)
            val interp = Interpreter(SingleRowDoc("/Order", c.values, kinds)).fires(parseCondition(c.text, "/Order"))
            Executable {
                assertEquals(kernel, interp, "\"${c.text}\" over ${c.values}/${c.empties} [k=$kernel i=$interp]")
            }
        }
        assertAll(checks)
    }

    @Test
    fun parsedStarredAggregateAgreesWithKernel() {
        val text = "FieldFilled(CustomerName) And Sum(Items*/Count) == 80"
        val model = RuntimeLaws.withRule(order, "/Order", "SumParse", "../CustomerName", "SUM_PARSE", text)
        val counts = listOf("40", null, "40")
        val kernel = RuntimeLaws.fires(model, "SUM_PARSE", items(counts))
        val interp = Interpreter(rep(counts).outerContext()).fires(parseCondition(text, "/Order"))
        assertEquals(kernel, interp, "parsed Sum(Items*/Count) == 80")
    }

    @Test
    fun parsedPerRowConditionAgreesWithKernel() {
        val text = "FieldFilled(Items/Count) And [Items/Count] > 50"
        val model = RuntimeLaws.withRule(order, "/Order", "CountCap", "../Items/Count", "COUNT_HIGH", text)
        val counts = listOf("10", "80", "90")
        val rx = Regex("""\[1, (\d+), 1]""")
        val kernel = RuntimeLaws.messages(model, items(counts)).filter { it.code() == "COUNT_HIGH" }
            .mapNotNull { rx.find(it.errorField())?.groupValues?.get(1)?.toInt() }.toSet()
        val ast = parseCondition(text, "/Order")
        val interp = rep(counts).rowContexts()
            .mapIndexedNotNull { i, ctx -> if (Interpreter(ctx).fires(ast)) i + 1 else null }.toSet()
        assertEquals(kernel, interp, "parsed per-row rule fires on the same rows")
    }

    private fun items(counts: List<String?>): RuntimeLaws.Doc {
        val d = RuntimeLaws.doc().group("/Order", 1).field(CN, "Acme", 1, 1)
        counts.forEachIndexed { i, c ->
            val r = i + 1
            d.group("/Order/Items", 1, r).field(IS, ('A' + i).toString(), 1, r, 1)
            if (c != null) d.field(IC, c, 1, r, 1) else d.emptyField(IC, 1, r, 1)
        }
        return d
    }

    private fun rep(counts: List<String?>): RepDoc {
        val rows = counts.mapIndexed { i, c ->
            buildMap {
                put(IS, ('A' + i).toString())
                if (c != null) put(IC, c)
            }
        }
        return RepDoc("/Order", "/Order/Items", mapOf(CN to "Acme"), rows, kinds)
    }

    private companion object {
        const val Q = "/Order/Quantity"
        const val BQ = "/Order/BackorderQuantity"
        const val TC = "/Order/TermsConfirmed"
        const val PC = "/Order/ProductCode"
        const val CN = "/Order/CustomerName"
        const val IC = "/Order/Items/Count"
        const val IS = "/Order/Items/Sku"
    }
}
