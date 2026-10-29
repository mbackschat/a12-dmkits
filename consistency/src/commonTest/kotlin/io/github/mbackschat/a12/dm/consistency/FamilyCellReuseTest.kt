package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * That a family's verdict is answered from an earlier check when — and only when — every fact it read holds.
 *
 * **Two halves, and the pairing is the point.** L's acceptance in
 * `docs/archived/PORTABLE-CONSISTENCY-PROPOSAL.md` asks for counters that show reuse on an edit AND recomputation
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
        {"header":{"modelVersion":"28.4.0","id":"cells","locales":[{"code":"en_US"}]},
         "content":{"modelInfo":{"name":"model"},"modelRoot":{"rootGroups":[
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

        /** Every PER-DECLARATION key, whatever family owns it — the denominator a narrowness row needs. */
        fun perDeclarationKeys() = keys.filter { it.at != null }
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
    fun cachedConditionContributionsAreSelectedAgainAfterASiblingEdit() {
        val paths = CheckProducer.Condition(setOf(CheckFamily.CONDITION_REFERENCES)) { facts, declaration ->
            facts.pathContributions(declaration).filter { it.family == CheckFamily.CONDITION_REFERENCES }
                .map { it.contribution }
        }
        val syntaxFamilies = setOf(CheckFamily.CONDITION_LEXICALITY, CheckFamily.CONDITION_GRAMMAR,
            CheckFamily.CONDITION_COMPLETENESS)
        val syntax = CheckProducer.Condition(syntaxFamilies) { facts, declaration ->
            val execution = facts.conditionExecution(declaration)
            syntaxFamilies.mapNotNull { execution?.syntaxContribution(it) }
        }
        val original = prepared("FieldFilled(NoSuchField) And FieldFilled(NorThisOne)")
        val first = original.elements.single { it.declaredKind == DeclaredKind.RULE }
        fun withCondition(text: String) = first.copy(declaration = JsonObject(first.declaration +
            (first.kind to JsonObject(first.body!! + ("errorCondition" to JsonPrimitive(text))))))
        fun run(model: PreparedModel, carried: CheckCells = CheckCells.NONE,
            producers: List<CheckProducer> = listOf(paths, syntax)): Pair<ExecutedChecks, List<ConditionFinding>> {
            val facts = TrackedFactBase(model, ReadTranscript())
            val executed = executeChecks(facts, model, carried, producers)
            return executed to executed.selectedConditions(facts)
        }
        for (samePath in listOf(false, true)) {
            fun sibling(text: String): DeclaredElement {
                val changed = withCondition(text)
                if (samePath) return changed
                val name = first.name + "Other"
                return changed.copy(path = first.path + "Other", name = name, id = "r1",
                    declaration = JsonObject(changed.declaration + mapOf(
                        "name" to JsonPrimitive(name), "id" to JsonPrimitive("r1"))))
            }
            val before = original.copy(elements = original.elements + sibling("FieldFilled(Code)"))
            val after = original.copy(elements = original.elements + sibling("FieldFilled("))
            val (cold, coldFindings) = run(before)
            assertEquals(listOf("MVK_INVALID_ENTITY"), coldFindings.map { it.finding.code })
            val firstKey = CellKey(paths, DeclarationKey(first.path, 0))
            assertEquals(2, cold.cells.getValue(firstKey).contributions.size,
                "cells retain local results before first-failure selection")
            val (reused, editedFindings) = run(after, CheckCells(cold.cells))
            if (!samePath) assertTrue(cold.cells.getValue(firstKey) === reused.cells.getValue(firstKey))
            assertTrue(reused.reused > 0 && reused.reused < reused.cells.size)
            assertEquals(listOf("MVK_INVALID_ENTITY", "MVK_INCOMPLETE_INPUT"), editedFindings.map { it.finding.code })
            assertEquals(if (samePath) listOf(0, 1) else listOf(0, 0), editedFindings.map { it.owner.amongSamePath })
            assertEquals(editedFindings, run(after).second, "reused and cold ordered findings differ")
            assertEquals(listOf("MVK_INVALID_ENTITY"), run(after, CheckCells(reused.cells), listOf(paths))
                .second.map { it.finding.code })
            assertEquals(listOf("MVK_INCOMPLETE_INPUT"), run(after, CheckCells(reused.cells), listOf(syntax))
                .second.map { it.finding.code })
            val repaired = after.copy(elements = after.elements.mapIndexed { index, element ->
                if (index == original.elements.indexOf(first)) withCondition("FieldFilled(Code)") else element
            })
            val (last, remaining) = run(repaired, CheckCells(reused.cells))
            val siblingKey = CellKey(syntax, DeclarationKey(if (samePath) first.path else first.path + "Other",
                if (samePath) 1 else 0))
            if (!samePath) assertTrue(reused.cells.getValue(siblingKey) === last.cells.getValue(siblingKey))
            assertEquals(listOf("MVK_INCOMPLETE_INPUT"), remaining.map { it.finding.code })
            assertEquals(remaining, run(repaired).second)
        }
        val competing = prepared("FieldFilled(NoSuchField) And FieldFilled(")
        val (both, firstStop) = run(competing)
        assertEquals(listOf("MVK_INVALID_ENTITY"), firstStop.map { it.finding.code })
        assertEquals(listOf("MVK_INCOMPLETE_INPUT"), run(competing, CheckCells(both.cells), listOf(syntax))
            .second.map { it.finding.code }, "a disabled path producer must not veto the selected syntax result")

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
        // EITHER per-declaration arm, corrected 2026-09-18: filtering on [CheckProducer.PerDeclaration]
        // alone, a family moved to the scoped walk would drop out of this row's coverage while still
        // passing it — the control would read as green for a family it no longer measures.
        val perDeclaration = CHECK_PRODUCERS.filterNot { it is CheckProducer.ModelWide }
            .flatMap { it.completes }
        assertEquals(emptyList(), MIGRATED.filterNot { it in perDeclaration },
            "a family this row measures per declaration is no longer registered that way")
    }

    /**
     * The narrowness claim over ALL sixty per-declaration families, not the four the row above names.
     *
     * The per-family row asserts an EQUALITY — fell exactly at the edit and its ancestor — and that only
     * holds for a family which reads the edited rule's text; a field-scoped family reads no rule body and
     * correctly falls nowhere, so the equality cannot be generalized. What generalizes is the subset: a cell
     * at a declaration that is neither edited nor an ancestor of the edit must survive. That is the property
     * the aggregate counters cannot see — they can tell you 444 of 518 were reused and not WHICH 444 (cold
     * review, 2026-09-18, where "shown per family" covered 4 of 60).
     */
    @Test
    fun noPerDeclarationCellFallsAtADeclarationTheEditCannotReach() {
        val baseline = checked("FieldFilled(Code)")
        val edited = prepared("FieldFilled(Code) And FieldFilled(Tier)")

        val fallen = baseline.perDeclarationKeys().filter { baseline.cells.validIn(edited, it) == null }

        assertEquals(emptyList(), fallen.filterNot { it.at?.path in EDIT_TOUCHES }.map {
            "${it.producer.completes} @${it.at?.path}"
        }, "a per-declaration cell fell at a declaration the edit neither touched nor contains")
        // Non-vacuity, and in the direction that matters: a store refusing nothing would pass the assertion
        // above trivially. Something must fall at the edited rule itself.
        assertTrue(fallen.any { it.at?.path == "/Order/CodeRequired" },
            "nothing fell at the edited declaration, so this row is measuring a store that reuses everything")
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
        // scheduled cell must revalidate. It is the validator's own ceiling, and the row that makes the
        // partial figure above meaningful: a comparison that was subtly wrong — the shape value dragging in
        // bodies, a member left out of it — would fell cells here too, and a partial figure alone cannot
        // tell a correctly narrow store from a carelessly wide one. The denominator is `cellCount()` rather
        // than a literal because it is now `model-wide + per-declaration × declarations` and moves with the
        // fixture, so a migrated family changes it.
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

    /**
     * How many cells one check of the fixture schedules — the denominator the counters must add up to.
     *
     * Derived from a freshly prepared copy of the same fixture rather than from the snapshot under test,
     * which is a coupling a reader should see: the two agree because both build the identical JSON, and the
     * row below states the product this number IS so that the agreement is not the only thing holding it up.
     */
    private fun cellCount() = schedule(
        TrackedFactBase(prepared("FieldFilled(Code)"), ReadTranscript()),
    ).size

    /**
     * The product the published reuse figures are derived from, so the arithmetic cannot rot silently.
     *
     * `docs/CONSISTENCY-API.md` and the implementation plan publish cell counts for an eight-declaration
     * model — `38 + 60 × 8 = 518` on the day they were written. The FIGURES are dated evidence and move with
     * the registry; the FORM does not, and it is the form that makes them re-derivable: one cell per
     * model-wide producer plus one per per-declaration producer per declaration. Re-derive a current figure
     * with `./build.sh :consistency:jvmTest` and this row's own terms.
     */
    @Test
    fun theCellDenominatorIsTheProductThePublishedFiguresAreDerivedFrom() {
        val declarations = prepared("FieldFilled(Code)").elements.size
        val modelWide = CHECK_PRODUCERS.count { it is CheckProducer.ModelWide }
        val perDeclaration = CHECK_PRODUCERS.count { it !is CheckProducer.ModelWide }

        assertEquals(modelWide + perDeclaration * declarations, cellCount(),
            "one cell per model-wide producer plus one per declaration for the rest — $modelWide + " +
                "$perDeclaration x $declarations; a schedule that stopped obeying this makes every published " +
                "reuse figure unreadable")
        assertTrue(perDeclaration > modelWide,
            "most producers are per-declaration today, which is what the published figures are ABOUT; " +
                "$perDeclaration per declaration against $modelWide model-wide")
    }

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
