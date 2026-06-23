package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.Interpreter
import io.github.mbackschat.a12.dm.interpreter.Kind
import io.github.mbackschat.a12.dm.interpreter.SingleRowDoc
import io.github.mbackschat.a12.dm.interpreter.parseCondition
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.function.Executable

/**
 * Differential — binary arithmetic (`+ - *`, with `{ }` grouping) inside comparisons, parsed from text
 * and checked against the REAL kernel. Precedence is `*` over `+`/`-`; empty numeric operands read as 0.
 * (Division and power are deferred — they carry scale/rounding + div-by-zero→unknown semantics handled
 * in a later increment, KERNEL-FINDINGS §finding "Power Operator".)
 */
class ArithmeticDiffTest {

    private val order = RuntimeLaws.model("models/order.dm.json")
    private val kinds = mapOf(Q to Kind.NUMBER, BQ to Kind.NUMBER, CN to Kind.STRING)

    private data class Case(val text: String, val values: Map<String, String>)

    private val cases = listOf(
        // catalog example: fires when Quantity is below backorder + 2
        Case("FieldFilled(CustomerName) And [Quantity] < [BackorderQuantity] + 2", mapOf(CN to "Acme", Q to "5", BQ to "10")),
        Case("FieldFilled(CustomerName) And [Quantity] < [BackorderQuantity] + 2", mapOf(CN to "Acme", Q to "13", BQ to "10")),
        // subtraction
        Case("[Quantity] - [BackorderQuantity] > 3", mapOf(Q to "10", BQ to "5")),
        Case("[Quantity] - [BackorderQuantity] > 3", mapOf(Q to "10", BQ to "8")),
        // multiplication binds tighter than addition
        Case("[Quantity] > [BackorderQuantity] * 2 + 1", mapOf(Q to "21", BQ to "10")),
        Case("[Quantity] > [BackorderQuantity] * 2 + 1", mapOf(Q to "20", BQ to "10")),
        // curly-brace grouping overrides precedence
        Case("[Quantity] > [BackorderQuantity] * {1 + 1}", mapOf(Q to "21", BQ to "10")),
        // empty numeric operand reads as 0 in arithmetic
        Case("FieldFilled(CustomerName) And [Quantity] + [BackorderQuantity] < 1", mapOf(CN to "Acme")),
    )

    @Test
    fun arithmeticConditionsAgreeWithKernel() {
        val checks = cases.mapIndexed { i, c ->
            val code = "ARI_$i"
            val model = RuntimeLaws.withRule(order, "/Order", "A$i", "../Quantity", code, c.text)
            val kernel = RuntimeLaws.fires(model, code, setOf("/Order"), emptySet(), c.values)
            val interp = Interpreter(SingleRowDoc("/Order", c.values, kinds)).fires(parseCondition(c.text, "/Order"))
            Executable { assertEquals(kernel, interp, "\"${c.text}\" over ${c.values} [k=$kernel i=$interp]") }
        }
        assertAll(checks)
    }

    private companion object {
        const val Q = "/Order/Quantity"
        const val BQ = "/Order/BackorderQuantity"
        const val CN = "/Order/CustomerName"
    }
}
