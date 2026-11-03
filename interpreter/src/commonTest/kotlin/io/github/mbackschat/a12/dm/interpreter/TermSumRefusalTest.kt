package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * JVM + JS lock that kernel 31.1.1's term sum (`SumOfTerms`, KERNEL-FINDINGS KF656) is REFUSED, never evaluated or
 * silently skipped, while the interpreter does not implement it.
 *
 * The refusal is the plan audit's, so it holds before evaluation: the second rule puts the term sum behind an `Or`
 * whose first branch already decides the outcome, which an evaluation-time refusal would never reach. The control
 * rule shows the same model still evaluates a supported condition.
 */
class TermSumRefusalTest {

    private val model = ModelDef(
        fields = listOf(FieldDef(QTY, Kind.NUMBER), FieldDef(NOTE, Kind.STRING)),
        rules = listOf(
            RuleDef("$ROOT/TermSum", ROOT, "SumOfTerms(Items*, [Items/Qty]) > 0", "TERM_SUM", "../Note"),
            RuleDef("$ROOT/Shadowed", ROOT, "FieldNotFilled(Note) Or SumOfTerms(Items*, [Items/Qty]) > 0", "SHADOWED", "../Note"),
            RuleDef("$ROOT/Control", ROOT, "FieldNotFilled(Note)", "CONTROL", "../Note"),
        ),
        declaredReps = mapOf("$ROOT/Items" to 5),
    )

    @Test
    fun aTermSumIsReportedUnsupportedEvenWhereEvaluationWouldNotReachIt() {
        val report = DmInterpreter(model).validate(model.newDocument().group(ROOT, 1).group("$ROOT/Items", 1, 1)
            .field(QTY, "2", 1, 1, 1))
        assertEquals(listOf("$ROOT/Shadowed", "$ROOT/TermSum"), report.unsupported.map { it.rulePath }.sorted())
        assertEquals(listOf("CONTROL"), report.messages.map { it.code }, "the supported rule still evaluates")
    }

    private companion object {
        const val ROOT = "/Policy"
        const val QTY = "/Policy/Items/Qty"
        const val NOTE = "/Policy/Note"
    }
}
