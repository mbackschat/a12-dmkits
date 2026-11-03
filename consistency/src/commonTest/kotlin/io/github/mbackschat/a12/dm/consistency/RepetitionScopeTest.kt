package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * What the repetition-scope helpers answer about one repeatable shape.
 *
 * **It was an ARMS lock and is now a behaviour lock, because the second arm is gone.** While the estate was
 * part-migrated each helper had a `PreparedModel` twin, and the risk worth pinning was that the two fed a
 * separately built list to one shared decision — families on the old receiver would answer one way about a
 * repetition and migrated ones the other. Every family has since crossed, and on 2026-09-18 the compiler
 * settled the rest: main source builds with all four untracked arms renamed away, so they had no production
 * caller and the equalities here compared a live arm against one nothing but this file kept alive. That is
 * the vacuity the class's own header warned about, arriving from the other direction.
 *
 * **What survives is the expectations**, which is the documented drain for this shape
 * (`PORTABLE-CONSISTENCY-IMPLEMENTATION-PLAN.md`, the arms-lock paragraph): each case keeps the concrete
 * value it asserted, so the measured outcomes stay locked and only the self-comparison goes. Both directions
 * are kept for `crossesARepetitionOutside` — a constant-answer predicate would satisfy either alone.
 */
class RepetitionScopeTest {

    /** A repeatable claim list inside a policy, and a rule that names its own rule group. */
    private val policy = ModelSource(
        """
        {"header":{"modelVersion":"29.4.0","id":"policies","locales":[{"code":"en_US"}]},
         "content":{"modelInfo":{"name":"model"},"modelRoot":{"rootGroups":[
          {"type":"Group","id":"g0","name":"Policy","Group":{"elements":[
            {"type":"Field","id":"f0","name":"Holder","Field":{"fieldType":{"type":"StringType"}}},
            {"type":"Rule","id":"r0","name":"HolderNamed","Rule":{
              "errorEntityRelPath":"../Holder","errorCondition":"FieldFilled(RuleGroup/Holder)",
              "errorCode":"holder.required"}},
            {"type":"Group","id":"g1","name":"Claims","Group":{"repeatability":99,"elements":[
              {"type":"Field","id":"f1","name":"Amount","Field":{"fieldType":{"type":"NumberType"}}}
            ]}}
          ]}}
         ]}}}
        """.trimIndent(),
    )

    private fun tracked(): TrackedFactBase {
        val admitted = admit(policy)
        assertIs<Admission.Admitted>(admitted, "the fixture must be admissible")
        return prepare(admitted).tracked()
    }

    @Test
    fun aFieldBeneathARepeatableGroupIteratesThatGroup() {
        assertEquals(
            listOf("/Policy/Claims"),
            tracked().iteratedLevels("/Policy/Claims/Amount"),
            "the fixture's claim list must be the level this field iterates",
        )
    }

    @Test
    fun aRepeatableGroupItselfIsOneOfItsOwnIteratedLevels() {
        // The group carrier, which an ancestors-only walk would miss — `CurrentRepetition(Claims)` rides the
        // same marker a field beneath it does.
        assertEquals(
            listOf("/Policy/Claims"),
            tracked().iteratedLevels("/Policy/Claims"),
            "a reference ending AT a repeatable group iterates it too",
        )
    }

    @Test
    fun aReferenceReachingIntoARepetitionFromOutsideCrossesIt() {
        assertTrue(
            tracked().crossesARepetitionOutside("/Policy", "/Policy/Claims/Amount"),
            "declared at the policy and reading into the claim list, this must cross a repetition",
        )
    }

    @Test
    fun aDeclarationInsideTheRepetitionDoesNotCrossIt() {
        // The negative direction on the same fixture, so neither case rests on a predicate that answers one
        // way for everything.
        assertFalse(
            tracked().crossesARepetitionOutside("/Policy/Claims", "/Policy/Claims/Amount"),
            "a declaration inside the repetition addresses it rather than crossing it",
        )
    }

    @Test
    fun theCrossedRepetitionsAreNamedOutermostFirst() {
        assertEquals(
            listOf("/Policy/Claims"),
            tracked().crossedRepetitions("/Policy/Claims/Amount").map { it.path },
            "the fixture must really cross the claim list, or this locks an empty list",
        )
    }
}
