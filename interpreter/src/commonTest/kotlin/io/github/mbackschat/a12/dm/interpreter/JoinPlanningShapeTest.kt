package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.readDocumentJson
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Two join-planning shapes A12 refuses at the model, held to a deterministic, loud answer here because the
 * interpreter also reads models nobody checked (INTERPRETER-FINDINGS IF432).
 *
 * Own domain: an order whose keyed lines hold parts and keyed sub-lines, beside keyed picks holding bins.
 */
class JoinPlanningShapeTest {

    private val groups = setOf("/Order", "/Order/Lines", "/Order/Lines/Parts", "/Order/Lines/Subs", "/Order/Picks",
        "/Order/Picks/Bins", "/Order/Desk", "/Order/Desk/Calls")

    private fun model(vararg rules: RuleDef) = ModelDef(
        fields = listOf(
            FieldDef("/Order/Lines/LineNo", Kind.STRING), FieldDef("/Order/Lines/Parts/Count", Kind.NUMBER, scale = 0),
            FieldDef("/Order/Lines/Subs/LineNo", Kind.STRING), FieldDef("/Order/Lines/Subs/Note", Kind.STRING),
            FieldDef("/Order/Picks/LineNo", Kind.STRING), FieldDef("/Order/Picks/Bins/Qty", Kind.NUMBER, scale = 0),
            FieldDef("/Order/Desk/Calls/LineNo", Kind.STRING), FieldDef("/Order/Desk/Calls/Topic", Kind.STRING),
        ),
        rules = rules.toList(),
        groupPaths = groups,
        declaredReps = mapOf("/Order/Lines" to 5, "/Order/Lines/Parts" to 5, "/Order/Lines/Subs" to 3,
            "/Order/Picks" to 5, "/Order/Picks/Bins" to 3, "/Order/Desk/Calls" to 4),
        repeatableGroups = groups - setOf("/Order", "/Order/Desk"),
        indexFields = setOf("/Order/Lines/LineNo", "/Order/Lines/Subs/LineNo", "/Order/Picks/LineNo",
            "/Order/Desk/Calls/LineNo"),
    )

    private val document = """{"Order":{"Lines":[{"LineNo":"A","Parts":[{"Count":2}],"Subs":[{"LineNo":"A","Note":"n"}]}],""" +
        """"Picks":[{"LineNo":"A","Bins":[{"Qty":1}]},{"LineNo":"B","Bins":[{"Qty":1}]}],"Desk":{"Calls":[{"LineNo":"A","Topic":"t"}]}}}"""

    /**
     * A group target whose own level ties for deepest with another star's binding level. The target's own level
     * never iterates (KERNEL-FINDINGS KF603), so it must leave the choice before it is made: chosen first and then
     * dropped, the plan depended on which star was written first. A12 refuses this shape
     * (`MVK_ERROR_FIELD_NO_INDEX`, measured 2026-10-24), so only order-independence is asserted.
     */
    @Test
    fun theStarAnchorDoesNotDependOnTheOrderTheStarsAreWritten() {
        val lines = "Sum(/Order/Lines/Parts*/Count) > 0"
        val picks = "Sum(/Order/Picks/Bins*/Qty) > 0"
        fun outcome(condition: String): Pair<List<String>, List<String>> {
            val rule = RuleDef("/Order/Tie", "/Order", "GroupFilled(/Order/Lines) And $condition", "Tie", "../Lines")
            val m = model(rule)
            val result = DmInterpreter(m).validate(m.readDocumentJson(document))
            return result.messages.filter { it.code == "Tie" }.map { it.errorPath } to result.unsupported.map { it.rulePath }
        }
        assertEquals(outcome("$lines And $picks"), outcome("$picks And $lines"))
    }

    /**
     * A join whose sides include a keyed group and a keyed group inside it is declined, not evaluated: A12 refuses
     * a reference chain crossing two keyed levels beside a join (`MVK_MULTIPLE_INDEX_FIELDS`, KERNEL-FINDINGS KF602,
     * measured again 2026-10-24), so there is no runtime behaviour to match.
     */
    @Test
    fun aJoinedGroupInsideAnotherJoinedGroupIsDeclined() {
        val rule = RuleDef("/Order/Desk/Nested", "/Order/Desk",
            "FieldFilled(/Order/Desk/Calls/Topic) And FieldFilled(/Order/Lines/Subs/Note)", "Nested", "../Calls/Topic")
        val m = model(rule)
        val result = DmInterpreter(m).validate(m.readDocumentJson(document))
        assertEquals(listOf("/Order/Desk/Nested"), result.unsupported.map { it.rulePath })
        assertTrue(result.messages.none { it.code == "Nested" })
    }
}
