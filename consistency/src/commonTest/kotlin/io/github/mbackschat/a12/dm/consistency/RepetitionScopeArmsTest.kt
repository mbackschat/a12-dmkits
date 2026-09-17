package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The repetition-scope helpers answer the same on both receivers while the estate is part-migrated.
 *
 * **Why these two need their own lock, when the shared core is structural.** Each helper keeps one
 * implementation that both arms call, so the DECISION cannot diverge — what can is the INPUT each arm feeds
 * it, and one of the two feeds a list the arms build separately (`elements` on one side, the cursors on
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
    fun bothArmsAgreeOnARuleNamingItsOwnGroup() {
        val prepared = prepared()
        val declared = prepared.declaredReferences().single { it.declaredAt == "/Policy/HolderNamed" }

        val untracked = prepared.namesTheRulesOwnGroup(declared)
        val tracked = prepared.tracked().namesTheRulesOwnGroup(declared)

        assertTrue(untracked, "the fixture's rule must really name `RuleGroup`, or both arms answer false")
        assertEquals(untracked, tracked, "the two arms must agree that the rule names its own group")
    }
}
