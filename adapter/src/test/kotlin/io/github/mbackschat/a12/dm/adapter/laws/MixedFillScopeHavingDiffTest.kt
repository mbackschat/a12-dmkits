package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.MsgType
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
        const val CHARGE_TAG = "/Invoice/Charges/Tag"

        /** A SECOND filtered operand, so a list can hold two of them and no unfiltered star at all. */
        const val FILTERED_CHARGE =
            "/Invoice/Charges*/Amount Having [/Invoice/Charges/Tag] == \"T\""

        /** A bare (starred) GROUP operand, the other shape that diverts a list onto the scope path. */
        const val BARE_GROUP = "/Invoice/Charges*"
        const val CODE = "MFS"

        /** The filtered operand, spelled once so the subject and the control cannot drift apart. */
        const val FILTERED = "/Invoice/Lines*/Amount Having [/Invoice/Lines/Sku] == \"A\""

        /** The unfiltered sibling whose only job is to divert the dispatch onto the scope path. */
        const val UNFILTERED_SIBLING = "/Invoice/Charges*/Amount"
    }

    private val base = DocumentModelDraft.named("invoice").locale("en_US")
        .rootGroup("Invoice") { g ->
            g.stringField("Reference") { }
            g.group("Charges") { c ->
                c.repeatable(2).numberField("Amount") { it.fractionDigits(0, 2) }.stringField("Tag") { }
            }
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

    /**
     * The full `errorPath|type` signature of every message carrying [CODE], sorted — NOT a boolean.
     *
     * A boolean makes a message of the wrong TYPE read as agreement, and that is not hypothetical: the ninth
     * instance of this defect class shipped green under a boolean lock written in the same commit as its own fix,
     * and the tenth and thirteenth were both found by widening a projection rather than by adding a case. The
     * kernel's own verdict is still asserted as a boolean (`expected`), because that is what each row's document
     * is designed to produce; what is compared ACROSS engines is the signature.
     */
    private fun signature(messages: List<RuntimeLaws.Msg>): List<String> =
        messages.filter { it.code() == CODE }.map { "${it.field()}|${it.type().name}" }.sorted()

    private fun agreeFires(condition: String, expected: Boolean, msg: String, build: (Both) -> Unit) {
        val kModel = RuntimeLaws.withRule(base, "/Invoice", "R", "../Reference", CODE, condition)
        val def = ModelLoader.load(kModel)

        val dynamic = Both(RuntimeLaws.doc(), def.newDocument()).also(build)
        val kernelMessages = RuntimeLaws.messages(kModel, dynamic.kDoc)
        assertAdmitted(kernelMessages.map { it.code() }, msg)
        val groovy = signature(kernelMessages)

        val staticDoc = Both(RuntimeLaws.doc(), def.newDocument()).also(build).kDoc
        val javaStatic = signature(
            RuntimeLaws.kernelJavaEngine(kModel).messagesModern(staticDoc, null, java.util.Locale.US))

        val interpreter = DmInterpreter(def).validateFull(dynamic.iDoc).filter { it.code == CODE }
            .map { "${it.errorPath}|${if (it.type == MsgType.OMISSION) "OMISSION_ERROR" else "VALUE_ERROR"}" }
            .sorted()

        assertEquals(expected, groovy.isNotEmpty(), "$msg — the kernel groovy-dynamic verdict")
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
     * `AllFieldsFilled` on the same mixed list, measured rather than reasoned about. It is the one quantifier
     * whose scope-path answer comes from the DECLARED range (`fillScopeFires` → `allFilledDeclared`) instead of
     * the instantiated tally, so what a filter even MEANS for it is a kernel question and not a derivation — and
     * the fix must not invent an answer for it. Both declared rows are instantiated, so no un-instantiated tail
     * supplies its own explanation.
     */
    @Test
    fun theDeclaredRangeQuantifierAgreesOnTheSameMixedList() =
        agreeFires(guarded("AllFieldsFilled($UNFILTERED_SIBLING, $FILTERED)"), expected = false,
            msg = "mixed list/All: the kept row's Amount is empty, so not all are filled",
            build = ::theFilledCellIsInTheDroppedRow)

    /**
     * …and its companion on the document where every KEPT cell is filled. Read with the row above, this is what
     * pins whether the declared-range quantifier honours the filter at all: unfiltered, the dropped row's empty
     * `Amount` keeps it false; filtered, only the kept row counts.
     */
    @Test
    fun theDeclaredRangeQuantifierOverAFullyFilledKeptRow() =
        agreeFires(guarded("AllFieldsFilled($UNFILTERED_SIBLING, $FILTERED)"), expected = false,
            msg = "mixed list/All: the sibling's Charges rows are empty, so not all are filled") { b ->
            b.group("/Invoice", 1).field(REF, "I-1", 1, 1)
                .group(CHARGES, 1, 1)
                .group(CHARGES, 1, 2)
                .group(LINES, 1, 1).field(SKU, "A", 1, 1, 1).field(LINE_AMOUNT, "42.00", 1, 1, 1)
                .group(LINES, 1, 2).field(SKU, "B", 1, 2, 1)
        }

    /**
     * **THE DECLARED-RANGE DISCRIMINATOR**, and the reason the two rows above could not serve as one: in both of
     * them the sibling's `Charges` cells are empty, which makes `AllFieldsFilled` false whatever the filter does.
     * Here every `Charges` cell is filled, so the sibling cannot decide, and the two readings split: with the
     * filter honoured the only kept cell (`Lines[1]/Amount`) is filled and the quantifier fires; with the filter
     * dropped `Lines[2]/Amount` is empty and it does not. This is what gives the declared-range half of the fix a
     * measured basis instead of a derivation.
     */
    @Test
    fun theDeclaredRangeQuantifierHonoursTheFilterWhenTheSiblingCannotDecide() =
        agreeFires(guarded("AllFieldsFilled($UNFILTERED_SIBLING, $FILTERED)"), expected = true,
            msg = "mixed list/All: every Charges cell and the only KEPT Lines cell are filled") { b ->
            b.group("/Invoice", 1).field(REF, "I-1", 1, 1)
                .group(CHARGES, 1, 1).field(CHARGE_AMOUNT, "1.00", 1, 1, 1)
                .group(CHARGES, 1, 2).field(CHARGE_AMOUNT, "2.00", 1, 2, 1)
                .group(LINES, 1, 1).field(SKU, "A", 1, 1, 1).field(LINE_AMOUNT, "42.00", 1, 1, 1)
                .group(LINES, 1, 2).field(SKU, "B", 1, 2, 1)
        }

    /**
     * **(k) A filled-but-MALFORMED cell in a KEPT row.** `filteredFillScope` classifies it as unknown (so the
     * quantifier can be suppressed) where the sibling it is modelled on, `naiveStarQuantTally`, silently drops it;
     * it matches `Document.fillScope` instead. That was an unmeasured choice until this row — no other row places
     * a malformed cell in a row the filter keeps.
     */
    @Test
    fun aMalformedCellInAKeptRowIsClassifiedTheKernelsWay() =
        agreeFires(guarded("NoFieldFilled($UNFILTERED_SIBLING, $FILTERED)"), expected = false,
            msg = "the kept row's Amount is filled-but-malformed") { b ->
            b.group("/Invoice", 1).field(REF, "I-1", 1, 1)
                .group(CHARGES, 1, 1)
                .group(CHARGES, 1, 2)
                .group(LINES, 1, 1).field(SKU, "A", 1, 1, 1).field(LINE_AMOUNT, "5.555", 1, 1, 1)
                .group(LINES, 1, 2).field(SKU, "B", 1, 2, 1)
        }

    /*
     * (l1) TWO filtered stars and no unfiltered operand at all was measured here and moved OUT, because it does
     * not measure the escalation this file is about. The kernel ADMITS the shape; this evaluator refuses to
     * evaluate it — `validateFull` reports "unsupported operand Star in a field-reference position", because with
     * no operand qualifying as a fill scope the list falls to `quantTally`, whose `singleOrNull as? Expr.Star`
     * handles ONE starred operand and treats a second as a plain field reference. That is a capability gap with a
     * clean degrade rather than a wrong answer, so it is recorded in [IG136] rather than fixed inside a slice
     * about group expansion. Its polarity — which the reordered escalation would now type OMISSION — is
     * unobservable until the construct evaluates at all.
     */

    /**
     * **(l2) A bare GROUP operand beside a filtered star** — the other diverting shape, and the second
     * configuration the reorder changed without a measurement.
     */
    @Test
    fun aBareGroupOperandBesideAFilteredStar() =
        agreeFires(guarded("NoFieldFilled($BARE_GROUP, $FILTERED)"), expected = false,
            msg = "the bare group holds a filled Amount, so nothing-is-filled is false") { b ->
            b.group("/Invoice", 1).field(REF, "I-1", 1, 1)
                .group(CHARGES, 1, 1).field(CHARGE_AMOUNT, "7.50", 1, 1, 1)
                .group(CHARGES, 1, 2)
                .group(LINES, 1, 1).field(SKU, "A", 1, 1, 1)
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
