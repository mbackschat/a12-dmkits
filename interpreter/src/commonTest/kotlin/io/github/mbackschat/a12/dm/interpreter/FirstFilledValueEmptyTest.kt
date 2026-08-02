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
 * Multiplatform (JVM + JS) lock for IF80: `FirstFilledValue` over a selection with NO filled value computes the
 * fillable **0** (the kernel's `NumberCombiner.ZAHL_0`, its initial `value` when `first == true`), NOT CLEARED —
 * the same empty-as-0 as its Sum/Min/Max siblings. Covers the plain, no-rows, and Having-passes-nothing shapes
 * (the last is the shrunk real-corpus minimal). Kernel differential: `adapter.laws.FirstFilledValueDiffTest`
 * (validate) + the compute tri-check; this pins the interpreter's compute outcome on both platforms.
 */
class FirstFilledValueEmptyTest {

    private val TOTAL = "/Cart/Total"
    private val QTY = "/Cart/Lines/Qty"
    private val FLAG = "/Cart/Lines/Flag"

    private fun model(op: String) = ModelDef(
        fields = listOf(FieldDef(TOTAL, Kind.NUMBER, scale = 2), FieldDef(QTY, Kind.NUMBER, scale = 2), FieldDef(FLAG, Kind.STRING)),
        rules = emptyList(),
        computations = listOf(ComputationDef(TOTAL, "/Cart", op)),
        repeatableGroups = setOf("/Cart/Lines"),
        declaredReps = mapOf("/Cart/Lines" to 1000),
    )

    private fun outcomeOf(op: String, rows: List<Pair<String?, String?>>): ComputeOutcome {
        val m = model(op)
        val doc = m.newDocument().group("/Cart", 1).field(TOTAL, "0.01", 1, 1)
        rows.forEachIndexed { i, (qty, flag) ->
            val r = i + 1
            doc.group("/Cart/Lines", 1, r)
            if (qty != null) doc.field(QTY, qty, 1, r, 1)
            if (flag != null) doc.field(FLAG, flag, 1, r, 1)
        }
        return DmInterpreter(m).compute(doc).first { it.path == TOTAL }.outcome
    }

    private fun valueOf(op: String, rows: List<Pair<String?, String?>>): String? {
        val m = model(op)
        val doc = m.newDocument().group("/Cart", 1).field(TOTAL, "0.01", 1, 1)
        rows.forEachIndexed { i, (qty, _) -> if (qty != null) { doc.group("/Cart/Lines", 1, i + 1); doc.field(QTY, qty, 1, i + 1, 1) } }
        return DmInterpreter(m).compute(doc).first { it.path == TOTAL }.value
    }

    private val ff = "FirstFilledValue(Lines*/Qty)"
    private val ffHaving = "FirstFilledValue(Lines*/Qty Having FieldNotFilled(Lines/Flag))"

    // The fix is the OUTCOME: empty selection → VALUE (the fillable 0), NOT CLEARED. This target has no
    // `minFractionalDigits`, so the value renders canonical (`0`); a `minFractionalDigits`-≥1 target pads it to
    // the stored form (`0.00`) via [io.github.mbackschat.a12.dm.interpreter.Dec.display] (IF82).
    @Test fun emptyRowsComputeZero() =
        assertEquals(ComputeOutcome.VALUE, outcomeOf(ff, listOf(null to null, null to null)), "no filled Qty → VALUE 0, not CLEARED")
    @Test fun emptyRowsValueIsZero() =
        assertEquals("0", valueOf(ff, listOf(null to null)), "no filled Qty → the fillable 0")
    @Test fun noRowsComputesZero() =
        assertEquals(ComputeOutcome.VALUE, outcomeOf(ff, emptyList()), "no Lines rows → VALUE 0")
    @Test fun havingPassesNothingComputesZero() =   // every row's Flag filled → Having (FieldNotFilled(Flag)) passes none
        assertEquals(ComputeOutcome.VALUE, outcomeOf(ffHaving, listOf("10.00" to "x", "20.00" to "y")), "Having passes no row → VALUE 0")
    @Test fun firstFilledComputesThatValue() =   // control: a filled Qty computes that value, not 0
        assertEquals(ComputeOutcome.VALUE, outcomeOf(ff, listOf(null to null, "10.00" to null)), "first filled Qty → VALUE (that value)")
}
