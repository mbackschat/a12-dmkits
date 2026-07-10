package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.ComputationDef
import io.github.mbackschat.a12.dm.interpreter.model.ComputeOutcome
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Multiplatform (JVM + JS) lock for IF111 — a `FirstFilledValue` COMPUTATION consumes its star's cells only
 * UNTIL the first FILLED one (the kernel's `FirstValueCombiner.addValue` ignores later adds): an INVALID cell
 * poisons only BEFORE the first given cell; an invalid TAIL cell is invisible. The generic any-invalid star
 * poison stays the rule for the consume-every-row combiners (Sum/Min/Max). Kernel-verified by the tri-check
 * differential `adapter.laws.FirstFilledValueInvalidTailDiffTest`.
 */
class FirstFilledValueInvalidTailTest {

    private val RESULT = "/Order/Result"
    private val QTY = "/Order/Items/Qty"

    private val def = ModelDef(
        fields = listOf(FieldDef(RESULT, Kind.NUMBER, scale = 0), FieldDef(QTY, Kind.NUMBER, scale = 0)),
        rules = emptyList(),
        computations = listOf(ComputationDef(RESULT, "/Order", "FirstFilledValue(Items*/Qty)")),
        repeatableGroups = setOf("/Order/Items"),
    )

    private fun outcome(rows: List<String?>): String {
        val doc = def.newDocument().group("/Order", 1).field(RESULT, "999", 1, 1)
        rows.forEachIndexed { i, qty ->
            doc.group("/Order/Items", 1, i + 1)
            qty?.let { doc.field(QTY, it, 1, i + 1, 1) }
        }
        val c = DmInterpreter(def).compute(doc).first { it.path == RESULT }
        return when (c.outcome) {
            ComputeOutcome.VALUE -> "VALUE|${c.value}"
            ComputeOutcome.CLEARED -> "CLEARED"
            ComputeOutcome.ERRORED -> "ERRORED"
        }
    }

    @Test fun anInvalidTailCellIsInvisible() =
        assertEquals("VALUE|5", outcome(listOf("5", "07")))

    @Test fun anInvalidCellBeforeTheFirstFilledPoisons() =
        assertEquals("CLEARED", outcome(listOf("07", "5")))

    @Test fun anEmptyRowBeforeTheFirstFilledComputes() =
        assertEquals("VALUE|5", outcome(listOf(null, "5")))

    @Test fun anInvalidCellAfterAnEmptyRowStillPoisons() =
        assertEquals("CLEARED", outcome(listOf(null, "07", "5")))

    @Test fun allValidComputesTheFirst() =
        assertEquals("VALUE|5", outcome(listOf("5", "9")))

    @Test fun allEmptySelectionIsTheFillableZero() =
        assertEquals("VALUE|0", outcome(listOf(null, null)))
}
