package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.MsgType
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
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

    // Conditions are authored with the rulekit typed DSL ([m]) and rendered to the canonical string fed to BOTH
    // engines — no free-text condition literals. `Dsl.firstFilledNumber(Qty*)` is FirstFilledValue over a number list.
    private val m = BoundModel.ofJson(fuzz)
    private fun render(c: Condition): String = RuntimeLaws.render(c)

    /** Place one Line per [qtys] entry (ItemId always filled; Qty placed when the entry is non-null), run the
     *  `/Cart`-level `FirstFilledValue(Lines* / Qty)` rule, and assert both engines fire identically. */
    private fun agree(cond: String, qtys: List<String?>, msg: String) {
        val kModel = RuntimeLaws.withRule(fuzz, "/Cart", "FF", "../Lines/Qty", "FF", cond)
        val def = ModelDef(fields, listOf(RuleDef("/Cart/FF", "/Cart", cond, "FF", "../Lines/Qty")),
            repeatableGroups = setOf("/Cart/Lines"), declaredReps = mapOf("/Cart/Lines" to 1000), indexFields = setOf(ITEM))
        val kDoc = RuntimeLaws.doc().group("/Cart", 1)
        val iDoc = def.newDocument()
        qtys.forEachIndexed { i, qty ->
            val row = i + 1
            kDoc.group("/Cart/Lines", 1, row).field(ITEM, "K$row", 1, row, 1)
            iDoc.field(ITEM, "K$row", 1, row, 1)
            if (qty != null) { kDoc.field(QTY, qty, 1, row, 1); iDoc.field(QTY, qty, 1, row, 1) }
        }
        // Compare BOTH firing AND polarity (null = no fire): the value-vs-omission type is the dimension the
        // earlier firing-only check missed — an empty row BEFORE the first filled one makes it OMISSION
        // (filling it could displace the first value), else VALUE (the first value is fixed).
        val kernel = RuntimeLaws.typeOf(kModel, "FF", kDoc).map { it.name }.orElse(null)
        val interp = DmInterpreter(def).validateFull(iDoc).firstOrNull { it.code == "FF" }
            ?.type?.let { if (it == MsgType.OMISSION) "OMISSION_ERROR" else "VALUE_ERROR" }
        assertEquals(kernel, interp, "$msg  (kernel=$kernel interp=$interp)")
    }

    private val gt10 = render(Dsl.firstFilledNumber(m.flatten("/Cart/Lines*/Qty")).gt(10))
    private val lt5 = render(Dsl.firstFilledNumber(m.flatten("/Cart/Lines*/Qty")).lt(5))

    // The first FILLED row decides; an earlier empty row is skipped, a later row is invisible.
    @Test fun firesOnFirstFilledSkippingEmpty() = agree(gt10, listOf(null, "30", "5"), "first filled = 30 > 10")
    @Test fun doesNotFireWhenFirstFilledFails() = agree(gt10, listOf(null, "5", "99"), "first filled = 5, not > 10 (99 invisible)")
    @Test fun malformedAfterFirstIsInvisible() = agree(gt10, listOf("30", "-0", "99"), "first filled = 30 > 10 (-0 in row 2 not read)")
    @Test fun malformedAtFirstFilledSuppresses() = agree(gt10, listOf(null, "-0", "99"), "first filled = -0 (formal error) → suppressed")
    @Test fun noFilledRowDoesNotFire() = agree(gt10, listOf(null, null), "no filled Qty → no first value → not fire")
    @Test fun singleFilledRow() = agree(gt10, listOf("42"), "first filled = 42 > 10")

    // IF80: NO filled value → the kernel's NumberCombiner returns the fillable 0 (not non-check-relevant), so a
    // `< 5` FIRES (0 < 5) and is OMISSION (filling could raise it off 0). Before the fix the interpreter returned
    // Unknown → suppressed, so this DISTINGUISHES empty-as-0 from empty-as-unknown (the `> 10` cases could not).
    @Test fun emptyFirstFilledIsZeroSoLt5Fires() = agree(lt5, listOf<String?>(null, null), "no filled Qty → FirstFilledValue = 0 → 0 < 5 FIRES (OMISSION)")
    @Test fun emptyFirstFilledIsZeroSoGt10DoesNotFire() = agree(gt10, listOf<String?>(), "no rows → FirstFilledValue = 0 → 0 > 10 does not fire")

    // Polarity: an empty row BEFORE the first filled one → OMISSION (filling it could become the new first
    // value); a first-row-filled (or only-later-empty) case → VALUE (the first value is fixed). KERNEL-FINDINGS.
    @Test fun emptyBeforeFirstFilledIsOmission() = agree(gt10, listOf(null, "30"), "empty row precedes first filled (30) → OMISSION")
    @Test fun firstRowFilledIsValue() = agree(gt10, listOf("30", null), "first row filled (30), later empty → VALUE")
    @Test fun twoEmptyThenFilledIsOmission() = agree(gt10, listOf(null, null, "30"), "two empty rows precede first filled → OMISSION")

    private companion object {
        const val ITEM = "/Cart/Lines/ItemId"
        const val QTY = "/Cart/Lines/Qty"
    }
}
