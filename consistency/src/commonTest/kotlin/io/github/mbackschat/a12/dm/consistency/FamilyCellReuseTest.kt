package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
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

    private fun model(condition: String, tierFilter: String = "") = """
        {"header":{"id":"cells","locales":[{"code":"en_US"}]},
         "content":{"modelRoot":{"rootGroups":[
          {"type":"Group","id":"g0","name":"Order","Group":{"elements":[
            {"type":"Field","id":"f0","name":"Code","Field":{"fieldType":{"type":"StringType"}}},
            {"type":"Field","id":"f1","name":"Tier","Field":{$tierFilter"fieldType":{
              "type":"EnumerationType","EnumerationType":{"values":[{"value":"BASIC"}],
              "categories":[{"name":"Band","values":["A","B"]}]}}}},
            {"type":"Rule","id":"r0","name":"CodeRequired","Rule":{
              "errorCondition":"$condition","errorEntityRelPath":"../Code",
              "errorCode":"C1","errorMessage":[{"locale":"en_US","text":"the order code"}]}}
          ]}}
         ]}}}
    """.trimIndent()

    private fun prepared(condition: String, tierFilter: String = ""): PreparedModel {
        val admitted = admit(ModelSource(model(condition, tierFilter)))
        assertIs<Admission.Admitted>(admitted, "the fixture must be admissible")
        return prepare(admitted)
    }

    /** Every declaration as the SET SHAPE sees it — body excluded, which is what the control below needs. */
    private fun PreparedModel.shapeRows() = elements.map {
        listOf(it.path, it.name, it.kind, it.id, it.origin)
    }

    /** One check of [condition], as the store it leaves behind and the keys each family owns. */
    private class Checked(val cells: CheckCells, private val keys: List<CellKey>) {
        /** Every key [family] owns — one for a model-wide family, one per declaration for the others. */
        fun keysOf(family: CheckFamily) = keys.filter { family in it.producer.completes }
    }

    private fun checked(condition: String, tierFilter: String = ""): Checked {
        val base = prepared(condition, tierFilter)
        val tracked = TrackedFactBase(base, ReadTranscript())
        val scheduled = schedule(tracked)
        return Checked(
            CheckCells(scheduled.associate { it.key to tracked.cellFor(it.key, base, it.produce) }),
            scheduled.map { it.key },
        )
    }

    @Test
    fun aCellSurvivesAnEditToADeclarationItNeverRead() {
        val baseline = checked("FieldFilled(Code)")
        val edited = prepared("FieldFilled(Code) And FieldFilled(Tier)")
        val enumPairing = baseline.keysOf(CheckFamily.ENUM_CATEGORY_ALIGNMENT)

        val reusable = enumPairing.mapNotNull { baseline.cells.validIn(edited, it) }

        // The whole premise of per-family reuse, at the family whose scope is least arguable: the enum
        // pairing law reads a field's own `fieldType` and has no question about a rule at all.
        assertEquals(enumPairing.size, reusable.size,
            "an enum family read no rule body, so a condition edit cannot fell any of its cells")
        assertEquals(listOf("MVK_MISSING_INTERNAL_VALUE"), reusable.flatMap { it.findings }.map { it.code },
            "and the reused verdict must be the finding the broken fixture draws, not an empty list")
    }

    @Test
    fun eachMigratedFamilyFallsAtTheEditedDeclarationAndSurvivesAtTheOthers() {
        val baseline = checked("FieldFilled(Code)")
        val edited = prepared("FieldFilled(Code) And FieldFilled(Tier)")
        val declarations = prepared("FieldFilled(Code)").elements.size

        // The other direction, which is the one that would be UNSOUND to get wrong: a family that read the
        // edited body must not be answered from a verdict about the text before the keystroke. Three things
        // are asserted per family rather than in aggregate, because an aggregate count lets one family's
        // correct behaviour stand in for another's.
        //
        // WHICH cells fall is asserted rather than how many, and the answer is not the obvious one: a
        // GROUP's body nests its `elements`, so the rule's text is part of `/Order`'s body too and the
        // containing group's cell falls with it. That is sound, and it caps what a per-declaration key can
        // buy — every ancestor of an edit is recomputed, the root group on every keystroke — so it is
        // measured here rather than discovered while sizing the next batch.
        val wrong = MIGRATED.mapNotNull { family ->
            val keys = baseline.keysOf(family)
            val fell = keys.filter { baseline.cells.validIn(edited, it) == null }.mapNotNull { it.at?.path }
            // The positive control, per family, because a store that refused every cell would pass the row
            // above. An equal model is a different object with the same facts, which is the case reuse is for.
            val revalidated = keys.count { baseline.cells.validIn(prepared("FieldFilled(Code)"), it) != null }
            when {
                keys.size != declarations -> "$family owns ${keys.size} cells, not one per declaration"
                fell != EDIT_TOUCHES -> "$family fell at $fell"
                revalidated != keys.size -> "$family revalidated $revalidated of ${keys.size} on an equal model"
                else -> null
            }
        }

        assertEquals(emptyList(), wrong,
            "a migrated family's cells must fall at the edited declaration and its ancestors, and nowhere else")

        // …and the registration control, without which every row above passes on a family that never moved:
        // asked once per model, a family owns ONE cell, so `keys.size != declarations` would be the only
        // failure and the two interesting assertions would never be reached.
        val perDeclaration = CHECK_PRODUCERS.filterIsInstance<CheckProducer.PerDeclaration>()
            .flatMap { it.completes }
        assertEquals(emptyList(), MIGRATED.filterNot { it in perDeclaration },
            "a family this row measures per declaration is no longer registered that way")
    }

    @Test
    fun aCellFallsWhenAVariantFilterDeletesAReferentThoughNoDeclarationRowMoved() {
        val before = prepared("FieldFilled(Code)")
        // `ONLY` on a FIELD DELETES it rather than suppressing verdicts about it (KF305), so `Tier` leaves
        // the resolvable set while its path, name, kind, id and origin all stay exactly where they were.
        val after = prepared("FieldFilled(Code)", tierFilter = """"variantFilter":{"filterType":"ONLY"},""")
        val tracked = TrackedFactBase(before, ReadTranscript())
        val scheduled = schedule(tracked)
        val store = CheckCells(scheduled.associate { it.key to tracked.cellFor(it.key, before, it.produce) })

        // The control that gives this row its meaning: the two models are indistinguishable on the shape's
        // ROWS, so only the resolvable half of the shape value can tell them apart.
        assertEquals(before.shapeRows(), after.shapeRows(),
            "the filter must not move a declaration's row, or the rows would do this row's work")

        // The cells blind to `Tier`'s body. For each of them EVERY charged value is unchanged except the
        // resolvable set — so a shape value that left the resolvable set out would reuse a verdict reached
        // when `Tier` still existed, which is the unsound direction and the one no end-to-end row can reach
        // today: the only consumer carries cells across a DRAFT, and a draft can change a text member alone.
        val blind = scheduled.map { it.key }.filter { key ->
            val reads = tracked.observedWhileProducing(key)
            FactRead.DeclarationSetShape in reads && FactRead.DeclarationContent(TIER) !in reads
        }

        assertTrue(blind.isNotEmpty(), "no cell charged the set without Tier's body, so this is vacuous")
        assertEquals(
            emptyList(),
            blind.filter { store.validIn(after, it) != null }.map { it.producer.completes to it.at },
            "a cell that read the declaration SET must fall when a variant filter changes what resolves",
        )
    }

    @Test
    fun aKeystrokeReusesWhatItDidNotTouchAndRecomputesTheRest() {
        val result = snapshotOf(listOf(ModelSource(model("FieldFilled(Code)"))), "cells")
            .draft("/Order/CodeRequired", "errorCondition", "FieldFilled(NoSuchField)")
        val families = result.reuse.families

        assertTrue(families.reused > 0, "a keystroke in one rule must not fell every family's cell")
        assertTrue(families.recomputed > 0,
            "and the families that read that rule must be redone — ${families.reused} reused")
        assertEquals(cellCount(), families.reused + families.recomputed,
            "every cell is either reused or recomputed; a counter that drops one is not a denominator")
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

        assertEquals(cellCount(), families.reused,
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
        assertEquals(cellCount(), families.recomputed, "so every cell was produced")
    }

    /** How many cells one check of the fixture schedules — the denominator the counters must add up to. */
    private fun cellCount() = schedule(
        TrackedFactBase(prepared("FieldFilled(Code)"), ReadTranscript()),
    ).size

    private companion object {
        const val TIER = "/Order/Tier"

        /**
         * The families L-b3c-1 moved to a per-declaration key — the condition text ones, the dominant
         * editor case. Named rather than derived: the row above asserts the CONCRETE paths a condition
         * edit fells, which is only right for a family that reads condition text, and a derived
         * denominator would silently extend that claim to the next batch's field-scoped families.
         */
        val MIGRATED = listOf(
            CheckFamily.CONDITION_LEXICALITY,
            CheckFamily.CONDITION_COMPLETENESS,
            CheckFamily.CONNECTIVE_AGREEMENT,
            CheckFamily.CONDITION_GRAMMAR,
        )

        /** The bodies a keystroke in the rule's condition moves: its own, and the group that nests it. */
        val EDIT_TOUCHES = listOf("/Order", "/Order/CodeRequired")
    }
}
