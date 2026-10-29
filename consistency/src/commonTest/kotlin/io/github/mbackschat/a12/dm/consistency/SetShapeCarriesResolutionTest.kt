package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertNull
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The set shape has to carry what RESOLUTION depends on, and at a twinned path its key set did not.
 *
 * [FactRead.DeclarationSetShape]'s contract includes "whether the default variant deletes it", because a
 * `variantFilter` decides which declarations exist for resolution — a cell that resolved a reference must not
 * survive an edit that deleted its referent. The value carried `resolvableByPath.keys`, which keeps ONE entry
 * per path, while the short-name fallback counts over the resolvable LIST and reports `Ambiguous` at two. So
 * on the one model where a path is declared twice — a model A12 refuses and this checker reports on, which is
 * why the cell key carries an ordinal for it — deleting one twin left the shape value equal and changed what
 * a bare name resolves to (cold review, 2026-09-18).
 *
 * **Reachability, stated rather than implied.** Today's only consumer carries cells across a `draft`, which
 * moves one text member and no filter, so this was not reachable through `ConsistencySnapshot`. It is
 * measured here at the seam a cell actually crosses — a store built on one model, asked to answer for
 * another — which is what `FamilyCellReuseTest` already exercises and what any future carry-across-reparse
 * would use.
 */
class SetShapeCarriesResolutionTest {

    @Test
    fun aCellThatChargedTheSetShapeFallsWhenAFilterChangesWhatABareNameResolvesTo() {
        val ambiguous = prepared(TWINS)
        val resolved = prepared(TWIN_DELETED)

        // The discriminator, asserted rather than assumed: these two models differ in exactly what a bare
        // reference resolves to. Without this the row could pass on two models that resolve identically.
        assertIs<Reference.Ambiguous>(ambiguous.resolveEntity("/Order", "Code"),
            "the fixture must declare one path twice, so a bare name has two candidates")
        assertIs<Reference.Resolved>(resolved.resolveEntity("/Order", "Code"),
            "and its twin must be deleted at the default variant, so the same name resolves to one")

        val tracked = TrackedFactBase(ambiguous, ReadTranscript())
        val scheduled = schedule(tracked)
        val store = CheckCells(scheduled.associate { it.key to tracked.cellFor(it.key, ambiguous, it.produce) })
        val chargedTheShape = scheduled
            .filter { FactRead.DeclarationSetShape in tracked.observedWhileProducing(it.key) }

        assertTrue(chargedTheShape.isNotEmpty(),
            "no cell charged the set shape, so this row would hold whatever the shape value carried")
        assertEquals(emptyList(), chargedTheShape.filter { store.validIn(resolved, it.key) != null }.map {
            it.key.producer.completes.joinToString(", ")
        }, "a cell keyed on the set shape answered for a model that resolves a bare name differently")

        // The other half of the contract: the shape must not have collapsed onto the content. A cell that
        // charged neither the shape nor the edited body has nothing to do with this edit and must survive,
        // or the fix above would be a store that reuses nothing.
        assertTrue(
            scheduled.any { store.validIn(resolved, it.key) != null },
            "no cell at all survived, so the shape value now moves for edits it has no business seeing",
        )
    }

    @Test
    fun theDefaultVariantViewKeepsTheSurvivingDeclarationAtADuplicatePath() {
        for ((removed, kept) in listOf("f0" to "f1", "f1" to "f0")) {
            val facts = TrackedFactBase(prepared(filteredTwin(removed)), ReadTranscript())
            assertEquals(listOf(kept), facts.allDefaultVariantDeclarations()
                .filter { it.path == "/Order/Lines/Code" }.map { it.id })
            assertEquals(listOf("f0", "f1"), facts.allDeclarations()
                .filter { it.path == "/Order/Lines/Code" }.map { it.id })
            val original = facts.allDeclarations().single { it.id == removed }
            val scoped = facts.scopedTo(DeclarationKey(original.path, if (removed == "f0") 0 else 1), original)
            assertSame(facts.allDefaultVariantDeclarations(), scoped.allDefaultVariantDeclarations(),
                "a whole-set view stays shared even when the scope names a deleted declaration")
        }
    }

    @Test
    fun changingWhichTwinSurvivesInvalidatesTheSetButUnrelatedContentDoesNot() {
        val before = prepared(filteredTwin("f1"))
        val swapped = prepared(filteredTwin("f0"))
        val contentOnly = prepared(filteredTwin("f1").replace(
            "\"type\":\"NumberType\"",
            "\"type\":\"NumberType\",\"NumberType\":{\"maxFractionalDigits\":2}",
        ))
        assertEquals(before.resolvableElements.map { it.path }, swapped.resolvableElements.map { it.path })
        val tracked = TrackedFactBase(before, ReadTranscript())
        val cell = schedule(tracked).single { CheckFamily.ELEMENT_NAME_UNIQUENESS in it.key.producer.completes }
        val store = CheckCells(mapOf(cell.key to tracked.cellFor(cell.key, before, cell.produce)))
        assertNull(store.validIn(swapped, cell.key), "a different twin survives at the same path")
        assertNotNull(store.validIn(contentOnly, cell.key), "a scale edit does not change the declaration set")
    }

    @Test
    fun scopedDefaultVariantFieldsKeepIdentityAndChargeOnlyTheirOwnMembership() {
        val model = prepared(filteredTwin("f1"))
        val facts = TrackedFactBase(model, ReadTranscript())
        val wholeKey = CellKey(CHECK_PRODUCERS.single { CheckFamily.ENUM_CATEGORY_ALIGNMENT in it.completes })
        val fields = facts.whileProducing(wholeKey) { facts.defaultVariantFields() }
        assertEquals(listOf("f0", "f2"), fields.map { it.id })
        assertEquals(setOf(FactRead.DeclarationSetShape), facts.observedWhileProducing(wholeKey))
        val original = facts.allDeclarations()
        fields.forEach { field -> assertSame(original.single { it.id == field.id }, field) }
        val producer = CHECK_PRODUCERS.single { CheckFamily.ENUM_S_VALUE_DOMAIN in it.completes }
        for (cursor in original) {
            val at = DeclarationKey(cursor.path, if (cursor.id == "f1") 1 else 0)
            val key = CellKey(producer, at)
            val scoped = facts.scopedTo(at, cursor)
            val result = facts.whileProducing(key) { scoped.defaultVariantFields() }
            val survives = cursor.id in setOf("f0", "f2")
            assertEquals(if (survives) listOf(cursor.id) else emptyList(), result.map { it.id })
            if (survives) assertSame(cursor, result.single())
            val expectedReads = setOf(FactRead.DeclarationRow(at.path, at.amongSamePath)) +
                if (cursor.declaredKind == DeclaredKind.FIELD) setOf(FactRead.DeclarationContent(at.path)) else emptySet()
            assertEquals(expectedReads, facts.observedWhileProducing(key))
        }
    }

    @Test
    fun scopedFieldCellsFollowFilterChangesWithoutDependingOnOtherFields() {
        val before = prepared(filteredTwin("f1"))
        val swapped = prepared(filteredTwin("f0"))
        val unrelated = prepared(filteredTwin("f1").replace(
            "\"type\":\"NumberType\"",
            "\"type\":\"NumberType\",\"NumberType\":{\"maxFractionalDigits\":2}",
        ))
        val facts = TrackedFactBase(before, ReadTranscript())
        val cell = schedule(facts).single {
            CheckFamily.ENUM_S_VALUE_DOMAIN in it.key.producer.completes &&
                it.key.at == DeclarationKey("/Order/Lines/Code", 1)
        }
        val store = CheckCells(mapOf(cell.key to facts.cellFor(cell.key, before, cell.produce)))
        assertNull(store.validIn(swapped, cell.key))
        assertNotNull(store.validIn(unrelated, cell.key))
    }

    @Test
    fun defaultVariantFieldLookupKeepsIdentityAndChargesEveryCaller() {
        val unfiltered = TrackedFactBase(prepared(TWINS), ReadTranscript())
        val first = unfiltered.allDeclarations().single { it.id == "f0" }
        assertSame(first, unfiltered.defaultVariantFieldAt(first.path))
        for ((removed, kept) in listOf("f0" to "f1", "f1" to "f0")) {
            val facts = TrackedFactBase(prepared(filteredTwin(removed)), ReadTranscript())
            val original = facts.allDeclarations()
            val survivor = original.single { it.id == kept }
            val deleted = original.single { it.id == removed }
            val scope = DeclarationKey(deleted.path, if (removed == "f0") 0 else 1)
            val scoped = facts.scopedTo(scope, deleted)
            val keys = listOf(CheckFamily.GROUP_ORDERING_REFERENCES, CheckFamily.ELEMENT_NAME_UNIQUENESS)
                .map { family -> CellKey(CHECK_PRODUCERS.single { family in it.completes }) }
            for ((index, key) in keys.withIndex()) {
                val reader = if (index == 0) facts else scoped
                val found = assertNotNull(facts.whileProducing(key) { reader.defaultVariantFieldAt(survivor.path) })
                assertSame(survivor, found)
                assertEquals(setOf(FactRead.DeclarationSetShape), facts.observedWhileProducing(key))
                facts.whileProducing(key) { found.body }
                assertEquals(setOf(FactRead.DeclarationSetShape, FactRead.DeclarationContent(survivor.path)),
                    facts.observedWhileProducing(key))
            }
            assertSame(original.single { it.id == "f0" }, facts.declarationAt(survivor.path))
            assertNull(facts.defaultVariantFieldAt("/Order/Missing"))
            assertNull(facts.defaultVariantFieldAt("/Order/Lines"))
        }
    }

    @Test
    fun orderingLookupReuseIsNarrowerThanTheExistingGroupBodyRead() {
        fun indexed(id: String) = filteredTwin(id).replace(
            "\"id\":\"g1\",\"name\":\"Lines\",\"Group\":{",
            "\"id\":\"g1\",\"name\":\"Lines\",\"Group\":{\"indexFieldName\":\"Code\",",
        )
        val beforeJson = indexed("f1")
        val before = prepared(beforeJson)
        val swapped = prepared(indexed("f0"))
        val selectedType = prepared(beforeJson.replaceFirst(
            "\"type\":\"StringType\"",
            "\"type\":\"StringType\",\"StringType\":{\"noValueValidation\":true}",
        ))
        val unrelated = prepared(beforeJson.replace(
            "\"type\":\"NumberType\"",
            "\"type\":\"NumberType\",\"NumberType\":{\"maxFractionalDigits\":2}",
        ))
        val facts = TrackedFactBase(before, ReadTranscript())
        val cell = schedule(facts).single { CheckFamily.GROUP_ORDERING_REFERENCES in it.key.producer.completes }
        val produced = facts.cellFor(cell.key, before, cell.produce)
        assertEquals(emptyList(), produced.findings)
        assertEquals(listOf("MVK_INDEX_FIELD_NO_VALUE_VALIDATION"),
            selectedType.tracked().groupOrderingFindings().map { it.code })
        val store = CheckCells(mapOf(cell.key to produced))
        assertNull(store.validIn(swapped, cell.key))
        assertNull(store.validIn(selectedType, cell.key))
        assertEquals(setOf(FactRead.DeclarationContent("/Order")),
            produced.readValues.filter { (read, value) -> unrelated.valueOf(read) != value }.keys)

        val lookup = TrackedFactBase(before, ReadTranscript())
        val lookupCell = lookup.cellFor(cell.key, before) {
            lookup.defaultVariantFieldAt("/Order/Lines/Code")?.body
            CheckOutput(emptyList())
        }
        assertEquals(setOf(FactRead.DeclarationSetShape, FactRead.DeclarationContent("/Order/Lines/Code")),
            lookupCell.readValues.keys)
        val lookupStore = CheckCells(mapOf(cell.key to lookupCell))
        assertNull(lookupStore.validIn(swapped, cell.key))
        assertNull(lookupStore.validIn(selectedType, cell.key))
        assertNotNull(lookupStore.validIn(unrelated, cell.key))
    }

    private fun prepared(json: String): PreparedModel {
        val admitted = admit(ModelSource(json))
        assertIs<Admission.Admitted>(admitted, "the fixture must be admissible")
        return prepare(admitted)
    }

    private companion object {

        /**
         * `/Order/Lines/Code` declared twice, one level BELOW the group a reference resolves from.
         *
         * The nesting is what reaches the ambiguity: [PreparedModel.resolveEntity] tries the declaring group
         * first and only then the model-wide short name, so twins inside the declaring group resolve
         * positionally and never ask the fallback the count question. `fieldRefByShortNameAllowed` is the
         * gate on that second tier, so without it the same name is simply undeclared.
         */
        private val TWINS = """
            {"header":{"modelVersion":"28.4.0","id":"twins","locales":[{"code":"en_US"}]},
             "content":{"modelInfo":{"name":"model"},"modelConfig":{"fieldRefByShortNameAllowed":true},"modelRoot":{"rootGroups":[
              {"type":"Group","id":"g0","name":"Order","Group":{"elements":[
                {"type":"Group","id":"g1","name":"Lines","Group":{"elements":[
                  {"type":"Field","id":"f0","name":"Code","Field":{"fieldType":{"type":"StringType"}}},
                  {"type":"Field","id":"f1","name":"Code","Field":{"fieldType":{"type":"StringType"}}}
                ]}},
                {"type":"Field","id":"f2","name":"Total","Field":{"fieldType":{"type":"NumberType"}}},
                {"type":"Rule","id":"r0","name":"CodeRequired","Rule":{
                  "errorEntityRelPath":"../Total","errorCondition":"FieldFilled(Code)",
                  "errorCode":"code.required","errorMessage":[{"locale":"en_US","text":"x"}]}}
              ]}}
             ]}}}
        """.trimIndent()

        /** The same model with one twin deleted at the default variant — the body moves, the rows do not. */
        private val TWIN_DELETED = filteredTwin("f1")

        private fun filteredTwin(id: String) = TWINS.replace(
            """{"type":"Field","id":"$id","name":"Code","Field":{"fieldType":{"type":"StringType"}}}""",
            """{"type":"Field","id":"$id","name":"Code","Field":{"fieldType":{"type":"StringType"},
                "variantFilter":{"filterType":"ONLY"}}}""",
        )
    }
}
