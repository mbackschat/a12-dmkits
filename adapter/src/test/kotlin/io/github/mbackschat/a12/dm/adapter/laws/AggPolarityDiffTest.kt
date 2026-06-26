package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.ast.CmpOp
import io.github.mbackschat.a12.dm.interpreter.ast.Cond
import io.github.mbackschat.a12.dm.interpreter.ast.Expr
import io.github.mbackschat.a12.dm.interpreter.eval.Interpreter
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.MsgType
import io.github.mbackschat.a12.dm.interpreter.eval.RepDoc
import io.github.mbackschat.a12.dm.interpreter.dec
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.function.Executable

/**
 * Differential — the omitted-tail aggregate polarity (mirrors `AggregateUncertaintyLawsTest`). A
 * starred aggregate is OMISSION-typed while it could still grow — an empty operand OR an
 * un-instantiated tail below the declared repetition (`Items` declares 50) — and only VALUE once every
 * declared row is instantiated and filled. Differential vs the real kernel's `MessageType`.
 */
class AggPolarityDiffTest {

    private val order = RuntimeLaws.model("models/order.dm.json")
    private val kinds = mapOf(CN to Kind.STRING, IC to Kind.NUMBER, IS to Kind.STRING)

    // The aggregate sub-condition fed to the kernel is authored with the rulekit typed DSL ([m]) and rendered —
    // no free-text condition literals. The interpreter side still drives off the hand-built [ast]; only the
    // [condText] (the agg after the `FieldFilled(CustomerName) And` prefix the helper prepends) changes.
    private val m = BoundModel.ofJson(order)
    private val itemCounts = m.flatten("/Order/Items*/Count")
    private fun render(c: Condition): String = DslRenderer().render(c)

    private val filledCn = Cond.Pred("FieldFilled", listOf(Expr.Field(CN)))
    private fun ndvEq1() = Cond.And(
        filledCn,
        Cond.Cmp(CmpOp.EQ, Expr.Func("NumberOfDifferentValues", listOf(Expr.Star(IC))), Expr.NumLit(dec(1))),
    )
    private fun sumLt100() = Cond.And(
        filledCn,
        Cond.Cmp(CmpOp.LT, Expr.Func("Sum", listOf(Expr.Star(IC))), Expr.NumLit(dec(100))),
    )

    @Test
    fun starredAggregateTypingAgreesWithKernel() {
        assertAll(
            typeDiff("ndv, empty operand → OMISSION", "ONE_SIZE",
                render(Dsl.numberOfDifferentValues(itemCounts).eq(1)), ndvEq1(), listOf("5", null, "5")),
            typeDiff("ndv, 3-of-50 instantiated → OMISSION (fillable tail)", "ONE_SIZE",
                render(Dsl.numberOfDifferentValues(itemCounts).eq(1)), ndvEq1(), listOf("5", "5", "5")),
            typeDiff("sum, all 50 filled → VALUE (exhausted)", "TOTAL_LOW",
                render(Dsl.sum(itemCounts).lt(100)), sumLt100(), List(50) { "1" }),
        )
    }

    private fun typeDiff(name: String, code: String, condText: String, ast: Cond, counts: List<String?>): Executable {
        val model = RuntimeLaws.withRule(order, "/Order", code, "../CustomerName", code,
            "FieldFilled(CustomerName) And $condText")
        val kernel = RuntimeLaws.typeOf(model, code, items(counts)).map { it.name }.orElse(null)
        val interp = Interpreter(rep(counts).outerContext()).firedType(ast)
            ?.let { if (it == MsgType.OMISSION) "OMISSION_ERROR" else "VALUE_ERROR" }
        return Executable { assertEquals(kernel, interp, "$name  [kernel=$kernel interp=$interp]") }
    }

    private fun items(counts: List<String?>): RuntimeLaws.Doc {
        val d = RuntimeLaws.doc().group("/Order", 1).field(CN, "Acme", 1, 1)
        counts.forEachIndexed { i, c ->
            val r = i + 1
            d.group("/Order/Items", 1, r).field(IS, "S$r", 1, r, 1)
            if (c != null) d.field(IC, c, 1, r, 1) else d.emptyField(IC, 1, r, 1)
        }
        return d
    }

    private fun rep(counts: List<String?>): RepDoc {
        val rows = counts.mapIndexed { i, c ->
            buildMap {
                put(IS, "S${i + 1}")
                if (c != null) put(IC, c)
            }
        }
        return RepDoc("/Order", "/Order/Items", mapOf(CN to "Acme"), rows, kinds, declaredReps = 50)
    }

    private companion object {
        const val CN = "/Order/CustomerName"
        const val IC = "/Order/Items/Count"
        const val IS = "/Order/Items/Sku"
    }
}
