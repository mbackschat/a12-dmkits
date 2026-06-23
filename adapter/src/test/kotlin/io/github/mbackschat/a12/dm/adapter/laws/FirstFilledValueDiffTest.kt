package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Differential — **`FirstFilledValue(Lines* / Qty)`**: the first specified value over a repetition list, in row
 * order. The exact-match subtleties: unspecified rows are **skipped**; operands **after** the first filled one
 * are never read (a formal error there is invisible — the rule still fires); but a formal error **at** the first
 * filled operand makes the value non-evaluable and **suppresses** the rule. A `/Cart`-level rule over a varying
 * number of Lines; ItemId (the index) is filled per row so no `mandatoryField` intrudes. Firing must agree.
 */
class FirstFilledValueDiffTest {

    private val fuzz = RuntimeLaws.model("models/fuzz.dm.json")
    private val fields = listOf(
        FieldDef(ITEM, Kind.STRING), FieldDef(QTY, Kind.NUMBER, scale = 0),
    )

    /** Place one Line per [qtys] entry (ItemId always filled; Qty placed when the entry is non-null), run the
     *  `/Cart`-level `FirstFilledValue(Lines* / Qty)` rule, and assert both engines fire identically. */
    private fun agree(cond: String, qtys: List<String?>, msg: String) {
        val kModel = RuntimeLaws.withRule(fuzz, "/Cart", "FF", "../Lines/Qty", "FF", cond)
        val def = ModelDef(fields, listOf(RuleDef("/Cart/FF", "/Cart", cond, "FF", "../Lines/Qty")),
            repeatableGroups = setOf("/Cart/Lines"), declaredReps = mapOf("/Cart/Lines" to 1000), indexFields = setOf(ITEM))
        val kDoc = RuntimeLaws.doc().group("/Cart", 1)
        val iDoc = Document(def.kinds, def.declaredReps, def.unsignedFields, def.rangeConfig, def.dateFormats, def.scales)
        qtys.forEachIndexed { i, qty ->
            val row = i + 1
            kDoc.group("/Cart/Lines", 1, row).field(ITEM, "K$row", 1, row, 1)
            iDoc.field(ITEM, "K$row", 1, row, 1)
            if (qty != null) { kDoc.field(QTY, qty, 1, row, 1); iDoc.field(QTY, qty, 1, row, 1) }
        }
        val kernel = RuntimeLaws.fires(kModel, "FF", kDoc)
        val interp = DmInterpreter(def).validateFull(iDoc).any { it.code == "FF" }
        assertEquals(kernel, interp, "$msg  (kernel=$kernel interp=$interp)")
    }

    private val gt10 = "FirstFilledValue(Lines*/Qty) > 10"

    // The first FILLED row decides; an earlier empty row is skipped, a later row is invisible.
    @Test fun firesOnFirstFilledSkippingEmpty() = agree(gt10, listOf(null, "30", "5"), "first filled = 30 > 10")
    @Test fun doesNotFireWhenFirstFilledFails() = agree(gt10, listOf(null, "5", "99"), "first filled = 5, not > 10 (99 invisible)")
    @Test fun malformedAfterFirstIsInvisible() = agree(gt10, listOf("30", "-0", "99"), "first filled = 30 > 10 (-0 in row 2 not read)")
    @Test fun malformedAtFirstFilledSuppresses() = agree(gt10, listOf(null, "-0", "99"), "first filled = -0 (formal error) → suppressed")
    @Test fun noFilledRowDoesNotFire() = agree(gt10, listOf(null, null), "no filled Qty → no first value → not fire")
    @Test fun singleFilledRow() = agree(gt10, listOf("42"), "first filled = 42 > 10")

    private companion object {
        const val ITEM = "/Cart/Lines/ItemId"
        const val QTY = "/Cart/Lines/Qty"
    }
}
