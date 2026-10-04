package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.WorkLedger
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A filtered positive threshold consumes instantiated counts only, so its work must not grow with the declared
 * capacity of the filtered group (IG161). The document is held fixed — one filled direct field and one filled row —
 * and only the capacity moves; condition nodes count every filter evaluation, so a walk over declared rows shows up
 * as work proportional to the capacity. `AllFieldsFilled` over the same operand is the positive control: it does use
 * the declared range, and its work must grow, or the instrument could not see the walk at all.
 */
class FilteredThresholdDeclaredWorkTest {
    private fun work(operator: String, capacity: Int): Long {
        val model = ModelDef(
            listOf(FieldDef(DIRECT, Kind.NUMBER), FieldDef(QTY, Kind.NUMBER)),
            listOf(RuleDef("$ORDER/R", ORDER,
                "$operator(Direct, Rows*/Qty Having CurrentRepetition(Rows) == 1)", "R", "../Direct")),
            repeatableGroups = setOf(ROWS),
            declaredReps = mapOf(ROWS to capacity),
            groupPaths = setOf(ORDER, ROWS),
        )
        val doc = model.newDocument().group(ORDER, 1).field(DIRECT, "4", 1, 1)
            .group(ROWS, 1, 1).field(QTY, "2", 1, 1, 1)
        val ledger = WorkLedger()
        val outcome = DmInterpreter(model).also { it.workLedger = ledger }.validate(doc)
        assertEquals(emptyList(), outcome.unsupported, "$operator must be evaluable")
        return ledger.conditionNodes
    }

    @Test
    fun aFilteredThresholdDoesNoWorkOverUninstantiatedDeclaredRows() {
        for (operator in listOf("AtLeastOneFieldFilled", "MoreThanOneFieldFilled", "NotExactlyOneFieldFilled",
            "NoFieldFilled")) {
            assertEquals(work(operator, 3), work(operator, 30), "$operator walked the declared range")
        }
    }

    @Test
    fun theDeclaredRangeOperatorDoesGrowWithCapacity() {
        assertTrue(work("AllFieldsFilled", 30) > work("AllFieldsFilled", 3),
            "the control must see the declared walk, or the threshold equality above is vacuous")
    }

    private companion object {
        const val ORDER = "/Order"
        const val ROWS = "/Order/Rows"
        const val DIRECT = "/Order/Direct"
        const val QTY = "/Order/Rows/Qty"
    }
}
