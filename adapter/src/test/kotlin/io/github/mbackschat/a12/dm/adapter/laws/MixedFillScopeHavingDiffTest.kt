package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Differential + tri-check — a field-fill quantifier over a list that **mixes an unfiltered star with a
 * `Having`-filtered one**, the shape [IG136] item (a) names. Measured ADMITTED by the 2026-08-14 sweep
 * ([KF187](../../../../../../../../../docs/KERNEL-FINDINGS.md)), which is why it is runtime work.
 *
 * **The mechanism, and why the estate could not already see it.** A fill quantifier takes the group/starred-scope
 * path as soon as **any** operand qualifies (`hasFillScopeOperand`: a bare group, or a star with no `Having`).
 * That path is `fillScopeInfo`, which then loops over **every** operand and calls `c.fillScope(path, isGroup = …)`
 * — a function that **takes no `having` parameter at all**, so a filter cannot be honoured there even in
 * principle. The filtered operand is therefore tallied over ALL its rows.
 *
 * The escape is that the trigger and the victim must be different operands: with only a filtered star in the
 * list, `hasFillScopeOperand` is false, the quantifier takes the ordinary `quantCounts` path, and the filter is
 * honoured. So the defect needs a list holding **both** an unfiltered star (to divert the dispatch) and a
 * filtered one (to be mis-tallied) — and the estate contains no rule of that shape. That is what
 * [theFilterSurvivesWithoutAnUnfilteredSibling] establishes as the control: same document, same filtered
 * operand, filter honoured. Any divergence is then attributable to the *sibling operand's* presence alone.
 *
 * **`NoFieldFilled` and `AtLeastOneFieldFilled` are chosen deliberately over `AllFieldsFilled`.** Those two
 * resolve through `quantifierFires` on the scope path and the ordinary path alike, so the two spellings stay
 * comparable; `AllFieldsFilled` switches to the DECLARED range on the scope path only
 * (`fillScopeFires` → `allFilledDeclared`), which would make the control and the subject differ in a second
 * respect and confound the measurement. Both declared rows are instantiated everywhere below for the same
 * reason — an un-instantiated declared tail reads empty and would supply its own explanation.
 */
class MixedFillScopeHavingDiffTest {

    private companion object {
        const val REF = "/Invoice/Reference"
        const val CHARGES = "/Invoice/Charges"
        const val CHARGE_AMOUNT = "/Invoice/Charges/Amount"
        const val LINES = "/Invoice/Lines"
        const val LINE_AMOUNT = "/Invoice/Lines/Amount"
        const val SKU = "/Invoice/Lines/Sku"
        const val CODE = "MFS"

        /** The filtered operand, spelled once so the subject and the control cannot drift apart. */
        const val FILTERED = "/Invoice/Lines*/Amount Having [/Invoice/Lines/Sku] == \"A\""

        /** The unfiltered sibling whose only job is to divert the dispatch onto the scope path. */
        const val UNFILTERED_SIBLING = "/Invoice/Charges*/Amount"
    }

    private val base = DocumentModelDraft.named("invoice").locale("en_US")
        .rootGroup("Invoice") { g ->
            g.stringField("Reference") { }
            g.group("Charges") { c -> c.repeatable(2).numberField("Amount") { it.fractionDigits(0, 2) } }
            g.group("Lines") { l ->
                l.repeatable(2).numberField("Amount") { it.fractionDigits(0, 2) }.stringField("Sku") { }
            }
        }.build().toJson()

    private val m = BoundModel.ofJson(base)

    private fun guarded(body: String): String = "FieldFilled($REF) And $body"

    private class Both(val kDoc: RuntimeLaws.Doc, val iDoc: Document) {
        fun group(path: String, vararg reps: Int): Both {
            kDoc.group(path, *reps); iDoc.group(path, *reps); return this
        }

        fun field(path: String, v: String, vararg reps: Int): Both {
            kDoc.field(path, v, *reps); iDoc.field(path, v, *reps); return this
        }
    }

    private fun assertAdmitted(codes: List<String>, msg: String) =
        assertTrue(codes.none { it.startsWith("MVK_") }) {
            "$msg — the kernel REJECTED the shape (${codes.filter { it.startsWith("MVK_") }}), which would make " +
                "this a measurement about admission and every row a comparison of two silences"
        }

    private fun agreeFires(condition: String, expected: Boolean, msg: String, build: (Both) -> Unit) {
        val kModel = RuntimeLaws.withRule(base, "/Invoice", "R", "../Reference", CODE, condition)
        val def = ModelLoader.load(kModel)

        val dynamic = Both(RuntimeLaws.doc(), def.newDocument()).also(build)
        val kernelMessages = RuntimeLaws.messages(kModel, dynamic.kDoc)
        assertAdmitted(kernelMessages.map { it.code() }, msg)
        val groovy = kernelMessages.any { it.code() == CODE }

        val staticDoc = Both(RuntimeLaws.doc(), def.newDocument()).also(build).kDoc
        val javaStatic = RuntimeLaws.kernelJavaEngine(kModel)
            .messagesModern(staticDoc, null, java.util.Locale.US).any { it.code() == CODE }

        val interpreter = DmInterpreter(def).validateFull(dynamic.iDoc).any { it.code == CODE }

        assertEquals(expected, groovy, "$msg — the kernel groovy-dynamic verdict")
        assertEquals(groovy, javaStatic, "$msg — groovy vs java-static (tri-check)")
        assertEquals(groovy, interpreter, "$msg — kernel vs INTERPRETER")
    }

    /**
     * Everything the filter KEEPS is empty and the one filled cell is in the row the filter DROPS. So a tally
     * that honours the filter sees nothing filled, and a tally that ignores it sees one filled cell — the two
     * readings fall on opposite sides of both quantifiers below.
     */
    private fun theFilledCellIsInTheDroppedRow(b: Both) {
        b.group("/Invoice", 1).field(REF, "I-1", 1, 1)
            .group(CHARGES, 1, 1)
            .group(CHARGES, 1, 2)
            .group(LINES, 1, 1).field(SKU, "A", 1, 1, 1)
            .group(LINES, 1, 2).field(SKU, "B", 1, 2, 1).field(LINE_AMOUNT, "42.00", 1, 2, 1)
    }

    /**
     * **THE DISCRIMINATOR.** `NoFieldFilled` over the mixed list: every kept cell is empty, so it must fire. A
     * tally that dropped the `Having` would see `Lines[2]/Amount` filled and stay silent.
     */
    @Test
    fun theFilteredOperandIsTalliedThroughItsFilter() =
        agreeFires(guarded("NoFieldFilled($UNFILTERED_SIBLING, $FILTERED)"), expected = true,
            msg = "mixed list/No: every kept cell is empty; the one filled cell is in the dropped row",
            build = ::theFilledCellIsInTheDroppedRow)

    /**
     * The OPPOSITE-DIRECTION partner on the same document. `AtLeastOneFieldFilled` must stay silent, and an
     * unfiltered tally would fire. Reading the two together is what distinguishes "the filter was dropped" from
     * "this quantifier answered wrongly" — a single row admits both explanations.
     */
    @Test
    fun theOppositePolarityAgreesOnTheSameDocument() =
        agreeFires(guarded("AtLeastOneFieldFilled($UNFILTERED_SIBLING, $FILTERED)"), expected = false,
            msg = "mixed list/AtLeastOne: no kept cell is filled",
            build = ::theFilledCellIsInTheDroppedRow)

    /**
     * **THE CONTROL that localizes the defect to the SIBLING's presence.** The same filtered operand, alone in
     * its list, takes the ordinary tally path because nothing diverts the dispatch — so the filter is honoured
     * and `NoFieldFilled` fires. Without this row, a divergence above could be read as "this `Having` never
     * works", which is a different and much larger claim.
     */
    @Test
    fun theFilterSurvivesWithoutAnUnfilteredSibling() =
        agreeFires(guarded("NoFieldFilled($FILTERED)"), expected = true,
            msg = "control/No: the filtered operand alone honours its filter",
            build = ::theFilledCellIsInTheDroppedRow)

    /**
     * The positive control that the filter is doing real work at all: move the filled cell INTO the kept row and
     * `NoFieldFilled` must stop firing on every spelling. Without it, "fires" above could mean the quantifier
     * never saw any cell, which is the very failure mode this file is chasing elsewhere.
     */
    @Test
    fun aFilledCellInTheKEPTRowSilencesTheQuantifier() =
        agreeFires(guarded("NoFieldFilled($UNFILTERED_SIBLING, $FILTERED)"), expected = false,
            msg = "mixed list/No: the kept row's Amount is filled, so nothing-is-filled is false") { b ->
            b.group("/Invoice", 1).field(REF, "I-1", 1, 1)
                .group(CHARGES, 1, 1)
                .group(CHARGES, 1, 2)
                .group(LINES, 1, 1).field(SKU, "A", 1, 1, 1).field(LINE_AMOUNT, "42.00", 1, 1, 1)
                .group(LINES, 1, 2).field(SKU, "B", 1, 2, 1)
        }

    /**
     * The unfiltered sibling must still be tallied — otherwise a fix could "pass" the rows above by ignoring the
     * diverting operand instead of honouring the filter. Its cell is the only filled one anywhere.
     */
    @Test
    fun theUnfilteredSiblingIsStillTallied() =
        agreeFires(guarded("NoFieldFilled($UNFILTERED_SIBLING, $FILTERED)"), expected = false,
            msg = "mixed list/No: the sibling's Charges[1]/Amount is filled") { b ->
            b.group("/Invoice", 1).field(REF, "I-1", 1, 1)
                .group(CHARGES, 1, 1).field(CHARGE_AMOUNT, "7.50", 1, 1, 1)
                .group(CHARGES, 1, 2)
                .group(LINES, 1, 1).field(SKU, "A", 1, 1, 1)
                .group(LINES, 1, 2).field(SKU, "B", 1, 2, 1)
        }
}
