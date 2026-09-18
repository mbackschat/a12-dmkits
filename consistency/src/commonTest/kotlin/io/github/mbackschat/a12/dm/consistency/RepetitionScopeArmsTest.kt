package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The repetition-scope helpers answer the same on both receivers while the estate is part-migrated.
 *
 * **Why these three need their own lock, when the shared core is structural.** Each helper keeps one
 * implementation that both arms call, so the DECISION cannot diverge — what can is the INPUT each arm feeds
 * it, and both of them feed a list the arms build separately (`elements` on one side, the cursors on
 * the other). A divergence there is silent and splits the estate: the families still on [PreparedModel] would
 * answer one way about a repetition and the migrated ones the other, on the same model, with every family's
 * own test green because each family only ever calls one arm.
 *
 * Every case carries a positive control, because the equality of two empty lists is the failure these
 * helpers have — a repetition list that came back empty would make every scope law yield rather than fire.
 */
class RepetitionScopeArmsTest {

    /** A repeatable claim list inside a policy, and a rule that names its own rule group. */
    private val policy = ModelSource(
        """
        {"header":{"id":"policies","locales":[{"code":"en_US"}]},
         "content":{"modelRoot":{"rootGroups":[
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

    private fun prepared(): PreparedModel {
        val admitted = admit(policy)
        assertIs<Admission.Admitted>(admitted, "the fixture must be admissible")
        return prepare(admitted)
    }

    @Test
    fun bothArmsNameTheSameIteratedLevels() {
        val prepared = prepared()

        val untracked = prepared.iteratedLevels("/Policy/Claims/Amount")
        val tracked = prepared.tracked().iteratedLevels("/Policy/Claims/Amount")

        assertEquals(
            listOf("/Policy/Claims"),
            untracked,
            "the fixture's claim list must be the level this field iterates, or the equality below holds " +
                "between two empty lists and the helper could have stopped reading the model",
        )
        assertEquals(untracked, tracked, "the two arms must name the same iterated levels")
    }

    @Test
    fun bothArmsAgreeOnCrossingARepetition() {
        val prepared = prepared()

        // Declared at the policy, reading into the claim list: the reference crosses a repetition the
        // declaration does not iterate, which is the positive answer this predicate exists for.
        val untracked = prepared.crossesARepetitionOutside("/Policy", "/Policy/Claims/Amount")
        val tracked = prepared.tracked().crossesARepetitionOutside("/Policy", "/Policy/Claims/Amount")
        assertTrue(untracked, "the fixture must really cross a repetition, or both arms answer false alike")
        assertEquals(untracked, tracked, "the two arms must agree that the reference crosses a repetition")

        // The negative direction on the same fixture, so the equality is not between two constants: a
        // declaration INSIDE the claim list addresses the repetition and does not cross it.
        val inside = prepared.crossesARepetitionOutside("/Policy/Claims", "/Policy/Claims/Amount")
        assertTrue(!inside, "a declaration inside the repetition must not be reported as crossing it")
        assertEquals(
            inside,
            prepared.tracked().crossesARepetitionOutside("/Policy/Claims", "/Policy/Claims/Amount"),
            "the two arms must agree on the negative direction too",
        )
    }

    /**
     * The third pair, added 2026-09-18 when its arms stopped building their input the same way.
     *
     * This helper was the one pair in the file that was neither pinned here nor called from untracked code,
     * and it became the file's sharpest case that day: the barrier arm was rerouted to `repeatableGroups()`,
     * the memoized cursor view, while the untracked arm still filters `elements` by `isRepeatableGroup`.
     * That is exactly the divergence this class exists for — two arms feeding a separately built list to one
     * shared decision — and nothing was measuring it. Compared by PATH because the arms return different
     * element types, which is the same asymmetry.
     */
    @Test
    fun bothArmsNameTheSameCrossedRepetitions() {
        val prepared = prepared()

        val untracked = prepared.crossedRepetitions("/Policy/Claims/Amount").map { it.path }
        val tracked = prepared.tracked().crossedRepetitions("/Policy/Claims/Amount").map { it.path }

        assertEquals(
            listOf("/Policy/Claims"),
            untracked,
            "the fixture must really cross the claim list, or the equality below holds between two empty " +
                "lists and either arm could have stopped reading the model",
        )
        assertEquals(untracked, tracked, "the two arms must name the same crossed repetitions")
    }
}
