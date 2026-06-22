package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.CmpOp
import io.github.mbackschat.a12.dm.interpreter.Cond
import io.github.mbackschat.a12.dm.interpreter.Expr
import io.github.mbackschat.a12.dm.interpreter.Interpreter
import io.github.mbackschat.a12.dm.interpreter.Kind
import io.github.mbackschat.a12.dm.interpreter.SingleRowDoc
import io.github.mbackschat.a12.dm.interpreter.dec
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.function.Executable

/**
 * Differential test (proposal §9): the [Interpreter] must agree with the REAL kernel (via the
 * package-private [RuntimeLaws] oracle) on whether each rule fires — over the SAME document instance.
 * Covers a comparison, the empty-value TYPE asymmetry, and an operand-list aggregate. The condition
 * exists twice — as kernel text and as a hand-built interpreter AST.
 */
class InterpreterDiffTest {

    private val order = RuntimeLaws.model("models/order.dm.json")

    /** The miniature metadata bridge: the field kinds the empty-value asymmetry needs. */
    private val kinds = mapOf(
        Q to Kind.NUMBER,
        BQ to Kind.NUMBER,
        TC to Kind.CONFIRM,
        ES to Kind.BOOLEAN,
        PC to Kind.STRING,
        CN to Kind.STRING,
    )

    private data class Case(
        val name: String,
        val text: String,                 // kernel condition text
        val ast: Cond,                    // the equivalent interpreter AST
        val errorRel: String,
        val empties: Set<String> = emptySet(),
        val values: Map<String, String> = emptyMap(),
    )

    private val cases = listOf(
        Case("comparison: 200 not < 100 → no fire", "[Quantity] < 100",
            lt(f(Q), n(100)), "../Quantity", values = mapOf(Q to "200")),
        Case("comparison: 50 < 100 → fire", "[Quantity] < 100",
            lt(f(Q), n(100)), "../Quantity", values = mapOf(Q to "50")),
        Case("empty number in a filled row → 0 < 100 → fire", "[Quantity] < 100",
            lt(f(Q), n(100)), "../Quantity", values = mapOf(CN to "Acme")),
        Case("entirely empty row → not evaluated → no fire", "[Quantity] < 100",
            lt(f(Q), n(100)), "../Quantity", values = emptyMap()),
        Case("empty confirm → False → (!= True) fires", "[TermsConfirmed] != True",
            ne(f(TC), tru), "../TermsConfirmed", empties = setOf(TC), values = mapOf(CN to "Acme")),
        Case("confirm true → not (!= True) → no fire", "[TermsConfirmed] != True",
            ne(f(TC), tru), "../TermsConfirmed", values = mapOf(CN to "Acme", TC to "true")),
        Case("empty boolean → not evaluated → no fire", "[ExpressShipping] == True",
            eq(f(ES), tru), "../ExpressShipping", empties = setOf(ES), values = mapOf(CN to "Acme")),
        Case("boolean true → == True → fire", "[ExpressShipping] == True",
            eq(f(ES), tru), "../ExpressShipping", values = mapOf(CN to "Acme", ES to "true")),
        Case("empty string → not evaluated → no fire", "[ProductCode] == \"ABC\"",
            eq(f(PC), s("ABC")), "../ProductCode", empties = setOf(PC), values = mapOf(CN to "Acme")),
        Case("string match → fire", "[ProductCode] == \"ABC\"",
            eq(f(PC), s("ABC")), "../ProductCode", values = mapOf(CN to "Acme", PC to "ABC")),
        Case("FieldFilled guard suppresses empty-0 → no fire",
            "FieldFilled(Quantity) And [Quantity] < 100",
            Cond.And(Cond.Pred("FieldFilled", listOf(f(Q))), lt(f(Q), n(100))),
            "../Quantity", values = mapOf(CN to "Acme")),
        Case("FieldFilled guard, 50 filled → fire",
            "FieldFilled(Quantity) And [Quantity] < 100",
            Cond.And(Cond.Pred("FieldFilled", listOf(f(Q))), lt(f(Q), n(100))),
            "../Quantity", values = mapOf(Q to "50")),
        Case("operand-list Min(∅→0, 5) == 0 → fire", "Min([Quantity], [BackorderQuantity]) == 0",
            eq(Expr.Func("Min", listOf(f(Q), f(BQ))), n(0)), "../Quantity",
            empties = setOf(Q), values = mapOf(CN to "Acme", BQ to "5")),
        Case("operand-list Min(3, 5) == 0 → no fire", "Min([Quantity], [BackorderQuantity]) == 0",
            eq(Expr.Func("Min", listOf(f(Q), f(BQ))), n(0)), "../Quantity",
            values = mapOf(CN to "Acme", Q to "3", BQ to "5")),
    )

    @Test
    fun interpreterAgreesWithKernelOnFiring() {
        val checks = cases.mapIndexed { i, c ->
            val code = "DIFF_$i"
            val model = RuntimeLaws.withRule(order, "/Order", "R$i", c.errorRel, code, c.text)
            val kernelFires = RuntimeLaws.fires(model, code, setOf("/Order"), c.empties, c.values)
            val interpFires = Interpreter(SingleRowDoc("/Order", c.values, kinds)).fires(c.ast)
            Executable {
                assertEquals(kernelFires, interpFires,
                    "${c.name}  [kernel=$kernelFires interp=$interpFires]")
            }
        }
        assertAll(checks)
    }

    private companion object {
        const val Q = "/Order/Quantity"
        const val BQ = "/Order/BackorderQuantity"
        const val TC = "/Order/TermsConfirmed"
        const val ES = "/Order/ExpressShipping"
        const val PC = "/Order/ProductCode"
        const val CN = "/Order/CustomerName"

        val tru = Expr.BoolLit(true)
        fun f(p: String) = Expr.Field(p)
        fun n(i: Int) = Expr.NumLit(dec(i))
        fun s(v: String) = Expr.StrLit(v)
        fun lt(a: Expr, b: Expr) = Cond.Cmp(CmpOp.LT, a, b)
        fun eq(a: Expr, b: Expr) = Cond.Cmp(CmpOp.EQ, a, b)
        fun ne(a: Expr, b: Expr) = Cond.Cmp(CmpOp.NE, a, b)
    }
}
