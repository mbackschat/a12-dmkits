package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.WorkLedger
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.Requiredness
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * One unavailable cell in a correlated join column must not hand the whole scope to the nested tally (IF423): the
 * indexed tally re-reads only the unavailable rows, so a scope with one such row does work linear in its rows. Every
 * row but the first carries its own required reference; the first's is empty, which KF552 makes unavailable.
 * Condition nodes count every filter evaluation, so the nested tally shows up as work growing with the square of the
 * rows. A compound filter, which no indexed plan accepts, is the positive control: it must grow quadratically, or the
 * linear bound above could not have seen the nested walk.
 */
class UnavailableCorrelationWorkTest {
    private fun work(having: String, rows: Int): Long {
        val model = ModelDef(
            listOf(FieldDef(REF, Kind.NUMBER, scale = 0, required = Requiredness.ABSOLUTE),
                FieldDef(AMOUNT, Kind.NUMBER, scale = 0)),
            listOf(RuleDef("$CLAIMS/R", CLAIMS,
                "FieldFilled(Amount) And AtLeastOneFieldFilled(../Claims*/Amount Having $having)", "R", "../Amount")),
            repeatableGroups = setOf(CLAIMS),
            declaredReps = mapOf(CLAIMS to 2 * ROWS),
            groupPaths = setOf(REGISTER, CLAIMS),
        )
        var doc = model.newDocument().group(REGISTER, 1)
        for (row in 1..rows) {
            doc = doc.group(CLAIMS, 1, row).field(AMOUNT, "1", 1, row, 1)
            if (row > 1) doc = doc.field(REF, "$row", 1, row, 1)
        }
        val ledger = WorkLedger()
        val outcome = DmInterpreter(model).also { it.workLedger = ledger }.validate(doc)
        assertEquals(emptyList(), outcome.unsupported, "$having must be evaluable")
        return ledger.conditionNodes
    }

    private fun growth(having: String) = work(having, 2 * ROWS).toDouble() / work(having, ROWS)

    @Test
    fun anUnavailableJoinCellKeepsTheIndexedTallyLinear() {
        for (having in listOf(KEYED, SELF_EXCLUDED, ORDERED)) {
            val growth = growth(having)
            assertTrue(growth < 2.5, "$having grew ${growth}x for twice the rows")
        }
    }

    @Test
    fun theNestedTallyDoesGrowQuadratically() {
        assertTrue(growth(NESTED) > 3.5,
            "the control must see the nested walk, or the linear bound above is vacuous")
    }

    private companion object {
        const val ROWS = 60
        const val REGISTER = "/Register"
        const val CLAIMS = "/Register/Claims"
        const val REF = "/Register/Claims/Reference"
        const val AMOUNT = "/Register/Claims/Amount"
        const val KEYED = "[Reference] == [\$Reference]"
        const val SELF_EXCLUDED = "CurrentRepetition($CLAIMS) != CurrentRepetition(\$$CLAIMS) And $KEYED"
        const val ORDERED = "[Reference] < [\$Reference]"
        const val NESTED = "[Reference] == [\$Reference] And FieldFilled(Amount)"
    }
}
