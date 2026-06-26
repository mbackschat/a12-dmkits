package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.eval.Interpreter
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.eval.SingleRowDoc
import io.github.mbackschat.a12.dm.interpreter.parse.parseCondition
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer
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

    // Conditions are authored with the rulekit typed DSL ([m]) and rendered to the canonical string fed to BOTH
    // engines (kernel injection + the interpreter parser) — no free-text condition literals, except where a
    // DSL-EXEMPT note marks a shape the renderer cannot express (precedence-overriding curly braces).
    private val m = BoundModel.ofJson(order)
    private fun render(c: Condition): String = DslRenderer().render(c)

    private data class Case(val text: String, val values: Map<String, String>)

    private val cases = listOf(
        // catalog example: fires when Quantity is below backorder + 2
        Case(render(m.field(CN).filled().and(m.num(Q).lt(m.num(BQ).asNumber().plus(2)))), mapOf(CN to "Acme", Q to "5", BQ to "10")),
        Case(render(m.field(CN).filled().and(m.num(Q).lt(m.num(BQ).asNumber().plus(2)))), mapOf(CN to "Acme", Q to "13", BQ to "10")),
        // subtraction
        Case(render(m.num(Q).asNumber().minus(m.num(BQ).asNumber()).gt(3)), mapOf(Q to "10", BQ to "5")),
        Case(render(m.num(Q).asNumber().minus(m.num(BQ).asNumber()).gt(3)), mapOf(Q to "10", BQ to "8")),
        // multiplication binds tighter than addition: the flat render reproduces the natural-precedence text
        Case(render(m.num(Q).gt(m.num(BQ).asNumber().times(Dsl.number("2")).plus(1))), mapOf(Q to "21", BQ to "10")),
        Case(render(m.num(Q).gt(m.num(BQ).asNumber().times(Dsl.number("2")).plus(1))), mapOf(Q to "20", BQ to "10")),
        // DSL-EXEMPT: curly-brace grouping overrides `*`-over-`+` precedence; the renderer emits flat infix
        // (no `{ }` for nested arithmetic), so `[BQ] * {1 + 1}` cannot be expressed via the typed builder.
        Case("[Quantity] > [BackorderQuantity] * {1 + 1}", mapOf(Q to "21", BQ to "10")),
        // empty numeric operand reads as 0 in arithmetic
        Case(render(m.field(CN).filled().and(m.num(Q).asNumber().plus(m.num(BQ).asNumber()).lt(1))), mapOf(CN to "Acme")),
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
