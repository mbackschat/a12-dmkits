package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.spike.CmpOp
import io.github.mbackschat.a12.dm.interpreter.spike.Cond
import io.github.mbackschat.a12.dm.interpreter.spike.Expr
import io.github.mbackschat.a12.dm.interpreter.spike.Interpreter
import io.github.mbackschat.a12.dm.interpreter.spike.Kind
import io.github.mbackschat.a12.dm.interpreter.spike.MsgType
import io.github.mbackschat.a12.dm.interpreter.spike.SpikeDoc
import io.github.mbackschat.a12.dm.interpreter.spike.dec
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.function.Executable

/**
 * SPIKE gap-close — the VALUE-vs-OMISSION message typing (mirrors `ErrorTypeLawsTest`). Differential vs
 * the real kernel's `IMessage.MessageType`: a comparison over filled values → VALUE; over an
 * empty-substituted field → OMISSION; And → omission wins; Or → value wins; `FieldNotFilled` → OMISSION.
 */
class PolarityDiffTest {

    private val order = RuntimeLaws.model("models/order.dm.json")
    private val kinds = mapOf(Q to Kind.NUMBER, BQ to Kind.NUMBER, CN to Kind.STRING)

    private data class Case(
        val name: String,
        val text: String,
        val ast: Cond,
        val errorRel: String,
        val empties: Set<String> = emptySet(),
        val values: Map<String, String> = emptyMap(),
    )

    private val cases = listOf(
        Case("filled comparison → VALUE", "[Quantity] > 5",
            cmp(CmpOp.GT, Q, 5), "../Quantity", values = mapOf(Q to "10")),
        Case("empty-substituted comparison → OMISSION", "[Quantity] >= 0",
            cmp(CmpOp.GE, Q, 0), "../Quantity", empties = setOf(Q), values = mapOf(CN to "Acme")),
        Case("And: value + omission → OMISSION", "[Quantity] > 5 And [BackorderQuantity] >= 0",
            Cond.And(cmp(CmpOp.GT, Q, 5), cmp(CmpOp.GE, BQ, 0)), "../Quantity",
            empties = setOf(BQ), values = mapOf(Q to "10")),
        Case("Or: value + omission → VALUE", "[Quantity] > 5 Or [BackorderQuantity] >= 0",
            Cond.Or(cmp(CmpOp.GT, Q, 5), cmp(CmpOp.GE, BQ, 0)), "../Quantity",
            empties = setOf(BQ), values = mapOf(Q to "10")),
        Case("And: value + value → VALUE", "[Quantity] > 5 And [BackorderQuantity] > 5",
            Cond.And(cmp(CmpOp.GT, Q, 5), cmp(CmpOp.GT, BQ, 5)), "../Quantity",
            values = mapOf(Q to "10", BQ to "10")),
        Case("Or: omission + omission → OMISSION", "[Quantity] >= 0 Or [BackorderQuantity] >= 0",
            Cond.Or(cmp(CmpOp.GE, Q, 0), cmp(CmpOp.GE, BQ, 0)), "../Quantity",
            empties = setOf(Q, BQ), values = mapOf(CN to "Acme")),
        Case("FieldNotFilled → OMISSION", "FieldNotFilled(Quantity)",
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
            val interp = Interpreter(SpikeDoc("/Order", c.values, kinds)).firedType(c.ast)
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
