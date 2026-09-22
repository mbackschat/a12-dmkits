package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
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
 * Differential — **`CurrentRepetition(Lines)`**: the current iteration row's **1-based** index for the group.
 * A `/Cart`-level rule that iterates the repeatable `/Cart/Lines` (the iteration scope is driven by the sibling
 * `FieldFilled(Lines/Qty)` ref — the kernel requires the rule to iterate the named group, else `MVK_NO_WILDCARD`).
 * `CurrentRepetition(Lines) > 1` therefore fires on rows 2, 3, … but not row 1. Firing must agree.
 */
class CurrentRepetitionDiffTest {

    private val fuzz = RuntimeLaws.model("models/fuzz.dm.json")
    private val fields = listOf(FieldDef(ITEM, Kind.STRING), FieldDef(QTY, Kind.NUMBER, scale = 0))

    // Conditions are authored with the rulekit typed DSL ([m]) and rendered to the canonical string fed to BOTH
    // engines (kernel injection + the interpreter) — no free-text condition literals.
    private val m = BoundModel.ofJson(fuzz)
    private fun render(c: Condition): String = RuntimeLaws.render(c)

    /** Instantiate [rows] Lines (each with ItemId + Qty="5" filled), run the `/Cart`-level rule, compare firing. */
    private fun agree(cond: String, rows: Int, msg: String) =
        agreeRows(cond, List(rows) { "5" }, msg)

    /** Instantiate one Line per [qtys] entry (ItemId always filled; Qty per entry — null = empty), compare firing. */
    private fun agreeRows(cond: String, qtys: List<String?>, msg: String) {
        val kModel = RuntimeLaws.withRule(fuzz, "/Cart", "CR", "../Lines/Qty", "CR", cond)
        val def = ModelDef(fields, listOf(RuleDef("/Cart/CR", "/Cart", cond, "CR", "../Lines/Qty")),
            repeatableGroups = setOf("/Cart/Lines"), declaredReps = mapOf("/Cart/Lines" to 1000), indexFields = setOf(ITEM))
        val kDoc = RuntimeLaws.doc().group("/Cart", 1)
        val iDoc = def.newDocument()
        qtys.forEachIndexed { i, qty ->
            val r = i + 1
            kDoc.group("/Cart/Lines", 1, r).field(ITEM, "K$r", 1, r, 1)
            iDoc.field(ITEM, "K$r", 1, r, 1)
            if (qty != null) { kDoc.field(QTY, qty, 1, r, 1); iDoc.field(QTY, qty, 1, r, 1) }
        }
        // Compare firing AND polarity (null = no fire): CurrentRepetition is the structural row index, so a
        // fired comparison against it is always VALUE (no fill can change the index) — the dimension a
        // firing-only check missed.
        val kernel = RuntimeLaws.typeOf(kModel, "CR", kDoc).map { it.name }.orElse(null)
        val interp = DmInterpreter(def).validateFull(iDoc).firstOrNull { it.code == "CR" }
            ?.type?.let { if (it == MsgType.OMISSION) "OMISSION_ERROR" else "VALUE_ERROR" }
        assertEquals(kernel, interp, "$msg  (kernel=$kernel interp=$interp)")
    }

    private val gt1 = render(m.field(QTY).filled().and(Dsl.currentRepetition(m.group(LINES)).gt(1)))
    private val gt2 = render(m.field(QTY).filled().and(Dsl.currentRepetition(m.group(LINES)).gt(2)))

    @Test fun firesFromSecondRow() = agree(gt1, 3, "rows 2,3 have rep > 1 → fires")
    @Test fun singleRowDoesNotFire() = agree(gt1, 1, "only row 1 (rep 1), not > 1 → no fire")
    @Test fun everyRowAtLeastOne() = agree(render(m.field(QTY).filled().and(Dsl.currentRepetition(m.group(LINES)).ge(1))), 2, "every row rep >= 1 → fires")
    @Test fun thresholdAboveRowCountDoesNotFire() = agree(gt2, 2, "max rep 2, not > 2 → no fire")
    @Test fun thirdRowCrossesThreshold() = agree(gt2, 3, "row 3 (rep 3) > 2 → fires")

    // PROBE (the fuzz divergence): a malformed Qty at row 3 — FieldFilled(Qty) is present, CurrentRepetition
    // doesn't read Qty's value, but the error field is formally invalid. Does the kernel suppress?
    @Test fun malformedErrorFieldAtFiringRow() = agreeRows(gt2, listOf("5", "5", "abc"), "row 3 Qty malformed (error field invalid)")
    // PROBE: a malformed Qty at a NON-firing row (index 1) — should not affect rows 2,3.
    @Test fun malformedAtNonFiringRow() = agreeRows(gt2, listOf("abc", "5", "5"), "row 1 Qty malformed, rows 2,3 valid")

    @Test
    fun aNonRepeatableGroupHasTheConstantIndexOneOnAllThreeEngines() {
        val order = RuntimeLaws.model("models/order.dm.json")
        val bound = BoundModel.ofJson(order)
        val guard = bound.str(CUSTOMER).filled()
        val equal = render(guard.and(Dsl.currentRepetition(bound.group(ORDER)).eq(1)))
        val unequal = render(guard.and(Dsl.currentRepetition(bound.group(ORDER)).ne(1)))
        val model = RuntimeLaws.withRule(
            RuntimeLaws.withRule(order, ORDER, "IndexOne", "../CustomerName", "CR_EQ", equal),
            ORDER, "IndexNotOne", "../CustomerName", "CR_NE", unequal,
        )
        val expected = setOf("CR_EQ")
        val kernelDocument = RuntimeLaws.doc().group(ORDER, 1).field(CUSTOMER, "Acme", 1, 1)
        val dynamic = RuntimeLaws.messages(model, kernelDocument)
            .map { it.code() }.filter { it == "CR_EQ" || it == "CR_NE" }.toSet()
        val static = RuntimeLaws.kernelJavaEngine(model)
        val generated = static.messages(static.instance(kernelDocument))
            .map { it.code() }.filter { it == "CR_EQ" || it == "CR_NE" }.toSet()
        val definition = ModelLoader.load(model)
        val interpreted = DmInterpreter(definition).validateFull(
            definition.newDocument().group(ORDER, 1).field(CUSTOMER, "Acme", 1, 1),
        ).map { it.code }.filter { it == "CR_EQ" || it == "CR_NE" }.toSet()

        assertEquals(expected, dynamic, "CurrentRepetition over a non-repeatable group is exactly 1")
        assertEquals(dynamic, generated, "dynamic Groovy vs generated Java")
        assertEquals(dynamic, interpreted, "dynamic Groovy vs interpreter")
    }

    private companion object {
        const val LINES = "/Cart/Lines"
        const val ITEM = "/Cart/Lines/ItemId"
        const val QTY = "/Cart/Lines/Qty"
        const val ORDER = "/Order"
        const val CUSTOMER = "/Order/CustomerName"
    }
}
