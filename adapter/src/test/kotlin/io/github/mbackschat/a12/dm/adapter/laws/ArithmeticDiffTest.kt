package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.eval.Interpreter
import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.eval.SingleRowDoc
import io.github.mbackschat.a12.dm.interpreter.parse.parseCondition
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import java.math.BigDecimal
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.function.Executable

/**
 * Differential — binary arithmetic (`+ - *`, with `{ }` grouping) inside comparisons, parsed from text
 * and checked against the REAL kernel. Precedence is `*` over `+`/`-`; empty numeric operands read as 0.
 * `×` caps at the kernel's `MathContext(50)` (the last-case test below); `÷`/`^` (div-by-zero→unknown, power
 * quiet cases) have their own differential in `DivPowDiffTest`.
 */
class ArithmeticDiffTest {

    private val order = RuntimeLaws.model("models/order.dm.json")
    private val kinds = mapOf(Q to Kind.NUMBER, BQ to Kind.NUMBER, CN to Kind.STRING)

    // Conditions are authored with the rulekit typed DSL ([m]) and rendered to the canonical string fed to BOTH
    // engines (kernel injection + the interpreter parser) — no free-text condition literals; the renderer now
    // braces a looser-binding nested arithmetic operand (DG7), so precedence-overriding groups are expressible.
    private val m = BoundModel.ofJson(order)
    private fun render(c: Condition): String = RuntimeLaws.render(c)

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
        // curly-brace grouping overrides `*`-over-`+` precedence: the renderer now braces a looser-binding
        // nested operand (DG7), so `times(plus(1, 1))` renders `[BackorderQuantity] * { 1 + 1 }`.
        Case(render(m.num(Q).gt(m.num(BQ).asNumber().times(Dsl.number("1").plus(1)))), mapOf(Q to "21", BQ to "10")),
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

    @Test
    fun precedenceAndGroupingUseDiscriminatingTriEngineCases() {
        val guard = m.str(CN).filled()
        val conditions = listOf(
            "multiply-before-add" to guard.and(Dsl.number("1").plus(Dsl.number("2").times(Dsl.number("3"))).eq("7")),
            "power-before-multiply" to guard.and(Dsl.number("2").times(
                Dsl.number("3").toThePowerOf(Dsl.number("2"))).eq("18")),
            "curly-group-overrides-precedence" to guard.and(Dsl.number("1").plus(Dsl.number("2")).times(
                Dsl.number("3")).eq("9")),
        )
        val model = conditions.foldIndexed(order) { index, json, (_, condition) ->
            RuntimeLaws.withRule(json, "/Order", "Precedence$index", "../CustomerName", "PREC_$index", render(condition))
        }
        val expected = conditions.indices.map { "PREC_$it" }.toSet()
        val kernelDocument = RuntimeLaws.doc().group("/Order", 1).field(CN, "Acme", 1, 1)
        val dynamic = RuntimeLaws.messages(model, kernelDocument).map { it.code() }.filter { it in expected }.toSet()
        val generatedEngine = RuntimeLaws.kernelJavaEngine(model)
        val generated = generatedEngine.messages(generatedEngine.instance(kernelDocument))
            .map { it.code() }.filter { it in expected }.toSet()
        val definition = ModelLoader.load(model)
        val interpreter = DmInterpreter(definition).validateFull(
            definition.newDocument().group("/Order", 1).field(CN, "Acme", 1, 1),
        ).map { it.code }.filter { it in expected }.toSet()

        assertEquals(expected, dynamic, "each expression distinguishes the published precedence from its rival parse")
        assertEquals(dynamic, generated, "dynamic Groovy vs generated Java")
        assertEquals(dynamic, interpreter, "dynamic Groovy vs interpreter")
    }

    /**
     * Multiplication caps at the kernel's `MathContext(50)` HALF_UP (`VkBigDecimal.INTERNAL_PRECISION`), like
     * `÷`/`^`. Two 26-digit factors make a 52-digit product, so the kernel drops its last 2 significant digits;
     * comparing the product for equality with its FULL-precision value is therefore FALSE in the kernel, and the
     * interpreter must agree. (Before the cap, `Dec.times` was full-precision and wrongly reported `==` — the
     * residual behind KERNEL-FINDINGS §5's "arithmetic ×/÷/^ cap".)
     */
    @Test
    fun multiplicationCapsAtMathContext50LikeTheKernel() {
        val a = "12345678901234567890123457"
        val b = "98765432109876543210987649"
        val exact = BigDecimal(a).multiply(BigDecimal(b)).toPlainString()  // 52 sig digits
        val cond = render(m.num(Q).asNumber().times(m.num(BQ).asNumber()).eq(exact))
        val model = RuntimeLaws.withRule(order, "/Order", "Mul", "../Quantity", "MUL", cond)
        val values = mapOf(Q to a, BQ to b)
        val kernel = RuntimeLaws.fires(model, "MUL", setOf("/Order"), emptySet(), values)
        val interp = Interpreter(SingleRowDoc("/Order", values, kinds)).fires(parseCondition(cond, "/Order"))
        assertEquals(kernel, interp, "product == full-precision: kernel caps at 50 sig digits [k=$kernel i=$interp]")
    }

    private companion object {
        const val Q = "/Order/Quantity"
        const val BQ = "/Order/BackorderQuantity"
        const val CN = "/Order/CustomerName"
    }
}
