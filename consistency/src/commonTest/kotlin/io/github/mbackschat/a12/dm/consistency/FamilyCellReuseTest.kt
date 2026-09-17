package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * That a family's verdict is answered from an earlier check when — and only when — every fact it read holds.
 *
 * **Two halves, and the pairing is the point.** L's acceptance in
 * `docs/PORTABLE-CONSISTENCY-PROPOSAL.md` asks for counters that show reuse on an edit AND recomputation
 * of what the edit affects, and says an always-full-recheck implementation fails the control.
 * So a row asserting reuse needs a row asserting the answer is unchanged beside it, and each is the other's
 * control: reuse with a wrong answer is the defect the store can have, and a right answer with no reuse is
 * the implementation the row refuses.
 *
 * The fixture's enumeration is deliberately BROKEN — one value against two category tokens — so the family
 * whose cell is reused carries a finding rather than an empty list. An empty verdict reused is
 * indistinguishable from an empty verdict recomputed, and every row here would pass on a store that returned
 * nothing at all.
 */
class FamilyCellReuseTest {

    private fun model(condition: String) = """
        {"header":{"id":"cells","locales":[{"code":"en_US"}]},
         "content":{"modelRoot":{"rootGroups":[
          {"type":"Group","id":"g0","name":"Order","Group":{"elements":[
            {"type":"Field","id":"f0","name":"Code","Field":{"fieldType":{"type":"StringType"}}},
            {"type":"Field","id":"f1","name":"Tier","Field":{"fieldType":{
              "type":"EnumerationType","EnumerationType":{"values":[{"value":"BASIC"}],
              "categories":[{"name":"Band","values":["A","B"]}]}}}},
            {"type":"Rule","id":"r0","name":"CodeRequired","Rule":{
              "errorCondition":"$condition","errorEntityRelPath":"../Code",
              "errorCode":"C1","errorMessage":[{"locale":"en_US","text":"the order code"}]}}
          ]}}
         ]}}}
    """.trimIndent()

    private fun prepared(condition: String): PreparedModel {
        val admitted = admit(ModelSource(model(condition)))
        assertIs<Admission.Admitted>(admitted, "the fixture must be admissible")
        return prepare(admitted)
    }

    /** The cells one check of [condition] leaves behind. */
    private fun cellsOf(condition: String): Pair<PreparedModel, CheckCells> {
        val base = prepared(condition)
        val tracked = TrackedFactBase(base, ReadTranscript())
        return base to CheckCells(CHECK_PRODUCERS.associateWith { cellOf(it, tracked, base) })
    }

    private fun producerOf(family: CheckFamily) = CHECK_PRODUCERS.single { family in it.completes }

    @Test
    fun aCellSurvivesAnEditToADeclarationItNeverRead() {
        val (_, cells) = cellsOf("FieldFilled(Code)")
        val edited = prepared("FieldFilled(Code) And FieldFilled(Tier)")
        val enumPairing = producerOf(CheckFamily.ENUM_CATEGORY_ALIGNMENT)

        val reusable = cells.validIn(edited, enumPairing)

        // The whole premise of per-family reuse, at the family whose scope is least arguable: the enum
        // pairing law reads a field's own `fieldType` and has no question about a rule at all.
        assertNotNull(reusable, "an enum family read no rule body, so a condition edit cannot fell its cell")
        assertEquals(listOf("MVK_MISSING_INTERNAL_VALUE"), reusable.findings.map { it.code },
            "and the reused verdict must be the finding the broken fixture draws, not an empty list")
    }

    @Test
    fun aCellFallsWhenTheDeclarationItReadIsEdited() {
        val (_, cells) = cellsOf("FieldFilled(Code)")
        val edited = prepared("FieldFilled(Code) And FieldFilled(Tier)")
        val grammar = producerOf(CheckFamily.CONDITION_GRAMMAR)

        // The other direction, which is the one that would be UNSOUND to get wrong: a family that read the
        // edited body must not be answered from a verdict about the text before the keystroke.
        assertNull(cells.validIn(edited, grammar), "a family that read the rule's body must be recomputed")

        // …and the positive control, because a store that refused every cell would pass the row above. An
        // equal model is a different object with the same facts, which is exactly the case reuse is for.
        assertNotNull(cells.validIn(prepared("FieldFilled(Code)"), grammar),
            "an equal model must revalidate, or the row above passes by refusing everything")
    }

    @Test
    fun aKeystrokeReusesWhatItDidNotTouchAndRecomputesTheRest() {
        val result = snapshotOf(listOf(ModelSource(model("FieldFilled(Code)"))), "cells")
            .draft("/Order/CodeRequired", "errorCondition", "FieldFilled(NoSuchField)")
        val families = result.reuse.families

        assertTrue(families.reused > 0, "a keystroke in one rule must not fell every family's cell")
        assertTrue(families.recomputed > 0,
            "and the families that read that rule must be redone — ${families.reused} reused")
        assertEquals(CHECK_PRODUCERS.size, families.reused + families.recomputed,
            "every producer is either reused or recomputed; a counter that drops one is not a denominator")
    }

    @Test
    fun aDraftThatChangesNothingReusesEveryFamily() {
        val source = ModelSource(model("FieldFilled(Code)"))
        val snapshot = snapshotOf(listOf(source), "cells")

        // Re-typing the text it already has leaves the drafted fact base equal to the baseline's, so every
        // one of the 98 cells must revalidate. It is the validator's own ceiling, and the row that makes the
        // partial figure above meaningful: a comparison that was subtly wrong — the shape value dragging in
        // bodies, a member left out of it — would fell cells here too, and 24-of-98 alone cannot tell a
        // correctly narrow store from a carelessly wide one.
        val families = snapshot.draft("/Order/CodeRequired", "errorCondition", "FieldFilled(Code)")
            .reuse.families

        assertEquals(CHECK_PRODUCERS.size, families.reused,
            "an unchanged model must revalidate every cell; ${families.recomputed} were recomputed")
    }

    @Test
    fun answeringFromTheCellsSaysExactlyWhatAFullRecheckSays() {
        val source = ModelSource(model("FieldFilled(Code)"))
        val stateless = checkDraft(source, "/Order/CodeRequired", "errorCondition",
            "FieldFilled(NoSuchField)")
        val fromCells = snapshotOf(listOf(source), "cells")
            .draft("/Order/CodeRequired", "errorCondition", "FieldFilled(NoSuchField)")

        // `checkDraft` carries no cells at all, so this is the store's answer against no store's answer —
        // compared on the whole report rather than on a projection of it.
        assertEquals(stateless, fromCells.edit, "the cells must change no answer, ever")
        assertTrue(fromCells.reuse.families.reused > 0,
            "and something must have been reused, or the equality above is about an unused store")
    }

    @Test
    fun aFreshSnapshotCarriesNoCellsInAndSaysSo() {
        val families = snapshotOf(listOf(ModelSource(model("FieldFilled(Code)"))), "cells").reuse.families

        // Structural rather than measured, and stated so a reader does not read `0` as a defect: this is the
        // check that BUILDS the store. Its counterpart is `preparationReused = false` on the same object.
        assertEquals(0, families.reused, "a first check has nothing to reuse")
        assertEquals(CHECK_PRODUCERS.size, families.recomputed, "so every producer ran")
    }
}
