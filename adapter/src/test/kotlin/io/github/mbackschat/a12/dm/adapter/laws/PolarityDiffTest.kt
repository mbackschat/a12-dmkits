package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.ast.CmpOp
import io.github.mbackschat.a12.dm.interpreter.ast.Cond
import io.github.mbackschat.a12.dm.interpreter.ast.Expr
import io.github.mbackschat.a12.dm.interpreter.eval.Interpreter
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.MsgType
import io.github.mbackschat.a12.dm.interpreter.eval.SingleRowDoc
import io.github.mbackschat.a12.dm.interpreter.dec
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.function.Executable

/**
 * Differential — the VALUE-vs-OMISSION message typing (mirrors `ErrorTypeLawsTest`). Differential vs
 * the real kernel's `IMessage.MessageType`: a comparison over filled values → VALUE; over an
 * empty-substituted field → OMISSION; And → omission wins; Or → value wins; `FieldNotFilled` → OMISSION.
 */
class PolarityDiffTest {

    private val order = RuntimeLaws.model("models/order.dm.json")
    private val kinds = mapOf(Q to Kind.NUMBER, BQ to Kind.NUMBER, CN to Kind.STRING)

    // The kernel-side condition text is authored with the rulekit typed DSL ([m]) and rendered — no free-text
    // condition literals. The interpreter side still drives off the hand-built [ast] (it asserts the typing the
    // AST produces equals the kernel's), so only the [text] fed to the kernel changes.
    private val m = BoundModel.ofJson(order)
    private fun render(c: Condition): String = RuntimeLaws.render(c)

    private data class Case(
        val name: String,
        val text: String,
        val ast: Cond,
        val errorRel: String,
        val empties: Set<String> = emptySet(),
        val values: Map<String, String> = emptyMap(),
    )

    private val cases = listOf(
        Case("filled comparison → VALUE", render(m.num(Q).gt(5)),
            cmp(CmpOp.GT, Q, 5), "../Quantity", values = mapOf(Q to "10")),
        Case("empty-substituted comparison → OMISSION", render(m.num(Q).ge(0)),
            cmp(CmpOp.GE, Q, 0), "../Quantity", empties = setOf(Q), values = mapOf(CN to "Acme")),
        Case("And: value + omission → OMISSION", render(m.num(Q).gt(5).and(m.num(BQ).ge(0))),
            Cond.And(cmp(CmpOp.GT, Q, 5), cmp(CmpOp.GE, BQ, 0)), "../Quantity",
            empties = setOf(BQ), values = mapOf(Q to "10")),
        Case("Or: value + omission → VALUE", render(m.num(Q).gt(5).or(m.num(BQ).ge(0))),
            Cond.Or(cmp(CmpOp.GT, Q, 5), cmp(CmpOp.GE, BQ, 0)), "../Quantity",
            empties = setOf(BQ), values = mapOf(Q to "10")),
        Case("And: value + value → VALUE", render(m.num(Q).gt(5).and(m.num(BQ).gt(5))),
            Cond.And(cmp(CmpOp.GT, Q, 5), cmp(CmpOp.GT, BQ, 5)), "../Quantity",
            values = mapOf(Q to "10", BQ to "10")),
        Case("Or: omission + omission → OMISSION", render(m.num(Q).ge(0).or(m.num(BQ).ge(0))),
            Cond.Or(cmp(CmpOp.GE, Q, 0), cmp(CmpOp.GE, BQ, 0)), "../Quantity",
            empties = setOf(Q, BQ), values = mapOf(CN to "Acme")),
        Case("FieldNotFilled → OMISSION", render(m.field(Q).notFilled()),
            Cond.Pred("FieldNotFilled", listOf(Expr.Field(Q))), "../Quantity",
            empties = setOf(Q), values = mapOf(CN to "Acme")),
    )

    @Test
    fun interpreterTypingAgreesWithKernel() {
        val checks = cases.mapIndexed { i, c ->
            val code = "POL_$i"
            val model = RuntimeLaws.withRule(order, "/Order", "R$i", c.errorRel, code, c.text)
            val kernel = RuntimeLaws.typeOf(model, code, setOf("/Order"), c.empties, c.values)
                .map { it.name }.orElse(null)
            val interp = Interpreter(SingleRowDoc("/Order", c.values, kinds)).firedType(c.ast)
                ?.let { if (it == MsgType.OMISSION) "OMISSION_ERROR" else "VALUE_ERROR" }
            Executable { assertEquals(kernel, interp, "${c.name}  [kernel=$kernel interp=$interp]") }
        }
        assertAll(checks)
    }

    private companion object {
        const val Q = "/Order/Quantity"
        const val BQ = "/Order/BackorderQuantity"
        const val CN = "/Order/CustomerName"

        fun cmp(op: CmpOp, path: String, n: Int) = Cond.Cmp(op, Expr.Field(path), Expr.NumLit(dec(n)))
    }
}
