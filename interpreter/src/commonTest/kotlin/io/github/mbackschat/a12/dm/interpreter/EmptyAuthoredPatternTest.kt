package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.ast.CmpOp
import io.github.mbackschat.a12.dm.interpreter.ast.Cond
import io.github.mbackschat.a12.dm.interpreter.ast.Expr
import io.github.mbackschat.a12.dm.interpreter.ast.Tri
import io.github.mbackschat.a12.dm.interpreter.eval.EvalConfig
import io.github.mbackschat.a12.dm.interpreter.eval.EmptyPatternFailure
import io.github.mbackschat.a12.dm.interpreter.eval.Interpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class EmptyAuthoredPatternTest {
    @Test
    fun reachedPatternsFailBeforeAvailabilityButRespectEarlierShortCircuit() {
        val field = FieldDef("/Parcel/Label", Kind.STRING)
        val model = ModelDef(fields = listOf(field, FieldDef("/Parcel/Ref", Kind.STRING)), rules = emptyList())
        val no = Cond.Cmp(CmpOp.EQ, Expr.StrLit("a"), Expr.StrLit("b"))
        for (raw in listOf(null, "", "ready")) for (matched in listOf(false, true)) {
            val document = model.newDocument().field("/Parcel/Ref", "parcel", 1, 1)
            if (raw != null) document.field(field.path, raw, 1, 1)
            val evaluator = Interpreter(document.computeContexts("/Parcel").single(), EvalConfig(computeMode = true))
            val pattern = Cond.Pattern(Expr.Field(field.path), Expr.StrLit(""), matched)
            assertFailsWith<EmptyPatternFailure> { evaluator.eval(pattern) }
            assertFailsWith<EmptyPatternFailure> { evaluator.eval(Cond.And(pattern, no)) }
            assertEquals(Tri.FALSE, evaluator.eval(Cond.And(no, pattern)))
            assertEquals(if (raw.isNullOrEmpty()) Tri.UNKNOWN else if (matched) Tri.TRUE else Tri.FALSE,
                evaluator.eval(pattern.copy(pattern = Expr.StrLit(".*"))))
        }
    }
}
