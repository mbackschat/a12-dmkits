package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals
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
            {"header":{"id":"twins","locales":[{"code":"en_US"}]},
             "content":{"modelConfig":{"fieldRefByShortNameAllowed":true},"modelRoot":{"rootGroups":[
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
        private val TWIN_DELETED = TWINS.replace(
            """{"type":"Field","id":"f1","name":"Code","Field":{"fieldType":{"type":"StringType"}}}""",
            """{"type":"Field","id":"f1","name":"Code","Field":{"fieldType":{"type":"StringType"},
                "variantFilter":{"filterType":"ONLY"}}}""",
        )
    }
}
