package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * The reference-addressing chain answers the same on both receivers while the estate is part-migrated.
 *
 * **Why the chain is locked at its top and not only member by member.** `addressedTargetOf` composes four
 * helpers that each now have two arms — the resolver, the star reader, the declaration's own iteration group
 * (itself over the relative resolver and the target-field group), and the iterated levels — so a divergence
 * anywhere inside it surfaces as one wrong answer at the top. This gate decides whether a whole class of
 * operand families judges an operand at all, so an arm that narrowed its answer would make the migrated
 * families go quiet on operands A12 refuses, and the unmigrated ones keep reporting them, on one model.
 *
 * Every case carries both directions of the answer, because the equality of two nulls is exactly the
 * failure this chain has: a null is how it says "not this family's operand".
 */
class ResolverChainArmsTest {

    /**
     * Two rules over one repeatable claim list, differing only in where their error field sits.
     *
     * That difference is the whole instrument: `ClaimAmountRequired` iterates the claim list because its
     * error field sits inside it, and `HolderRequired` does not — so the same written operand is addressed
     * for one and unaddressed for the other, and the chain's two outcomes are measured on ONE model.
     */
    private val policy = ModelSource(
        """
        {"header":{"id":"policies","locales":[{"code":"en_US"}]},
         "content":{"modelRoot":{"rootGroups":[
          {"type":"Group","id":"g0","name":"Policy","Group":{"elements":[
            {"type":"Field","id":"f0","name":"Holder","Field":{"fieldType":{"type":"StringType"}}},
            {"type":"Rule","id":"r0","name":"ClaimAmountRequired","Rule":{
              "errorEntityRelPath":"../Claims/Amount","errorCondition":"FieldFilled(Claims*/Amount)",
              "errorCode":"claim.amount.required"}},
            {"type":"Rule","id":"r1","name":"HolderRequired","Rule":{
              "errorEntityRelPath":"../Holder","errorCondition":"FieldFilled(Holder)",
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

    private fun PreparedModel.untracked(rule: String) = elements.single { it.path == "/Policy/$rule" }

    private fun PreparedModel.cursor(rule: String) =
        tracked().declarations().single { it.path == "/Policy/$rule" }

    @Test
    fun bothArmsNameTheSameStarredLevels() {
        val prepared = prepared()

        val untracked = prepared.starredLevelsOf("Claims*/Amount", "/Policy", prepared.untracked("ClaimAmountRequired"))
        val tracked = prepared.tracked()
            .starredLevelsOf("Claims*/Amount", "/Policy", prepared.cursor("ClaimAmountRequired"))

        assertEquals(
            setOf("/Policy/Claims"),
            untracked,
            "the star must land on the claim list, or the equality below holds between two empty sets and " +
                "no repeatability was ever read",
        )
        assertEquals(untracked, tracked, "the two arms must name the same starred levels")

        // The refusing direction: a star on something that is not a repeatable group is another family's
        // class entirely, reported as null rather than as an empty set.
        val starredField = prepared.starredLevelsOf("Holder*", "/Policy", prepared.untracked("HolderRequired"))
        assertNull(starredField, "a star on a field must refuse, not answer with no levels")
        assertEquals(
            starredField,
            prepared.tracked().starredLevelsOf("Holder*", "/Policy", prepared.cursor("HolderRequired")),
            "the two arms must refuse the same star",
        )
    }

    @Test
    fun bothArmsNameTheSameIterationGroup() {
        val prepared = prepared()

        // Also the case that discriminates `groupOfTargetField` and the relative resolver, which have no
        // other consumer on the tracked side: a wrong answer from either lands here.
        val untracked = prepared.iterationGroupOf(prepared.untracked("ClaimAmountRequired"))
        val tracked = prepared.tracked().iterationGroupOf(prepared.cursor("ClaimAmountRequired"))

        assertEquals(
            "/Policy/Claims",
            untracked,
            "the rule's error field sits in the claim list, so that is the level it iterates — an answer of " +
                "null would make the equality below vacuous and every operand read against the wrong group",
        )
        assertEquals(untracked, tracked, "the two arms must name the same iteration group")

        val atTheRoot = prepared.iterationGroupOf(prepared.untracked("HolderRequired"))
        assertEquals("/Policy", atTheRoot, "a rule whose error field sits at the policy iterates nothing below it")
        assertEquals(
            atTheRoot,
            prepared.tracked().iterationGroupOf(prepared.cursor("HolderRequired")),
            "the two arms must agree where the declaration iterates nothing",
        )
    }

    @Test
    fun bothArmsAddressTheSameTarget() {
        val prepared = prepared()

        val addressed = prepared.addressedTargetOf("Claims*/Amount", "/Policy", prepared.untracked("ClaimAmountRequired"))
        assertEquals(
            "/Policy/Claims/Amount",
            addressed,
            "the starred operand addresses the repetition the rule iterates, so the chain must hand its " +
                "target back — a null here would make both directions of this test compare nulls",
        )
        assertEquals(
            addressed,
            prepared.tracked()
                .addressedTargetOf("Claims*/Amount", "/Policy", prepared.cursor("ClaimAmountRequired")),
            "the two arms must address the same target",
        )

        // UNSTARRED, on the rule that DOES iterate the claim list — the one case that separates the
        // declaration's iteration group from its declaring group, and the case a mutant found missing: with
        // a star present, the level is addressed either way, so keying the chain on the declaring group
        // answered identically on every row above. A12 admits this operand, so a null here is a family going
        // silent on a model it should judge.
        val byIteration = prepared.addressedTargetOf("Claims/Amount", "/Policy", prepared.untracked("ClaimAmountRequired"))
        assertEquals(
            "/Policy/Claims/Amount",
            byIteration,
            "the rule's own iteration addresses the repetition without a star, so the chain must not refuse",
        )
        assertEquals(
            byIteration,
            prepared.tracked()
                .addressedTargetOf("Claims/Amount", "/Policy", prepared.cursor("ClaimAmountRequired")),
            "the two arms must address the target through the declaration's iteration alike",
        )

        // The same written operand on the rule that does NOT iterate the claim list: unstarred and
        // unaddressed, which is the reference reader's own class and not an operand family's.
        val unaddressed = prepared.addressedTargetOf("Claims/Amount", "/Policy", prepared.untracked("HolderRequired"))
        assertNull(unaddressed, "an unstarred crossing of a repetition the declaration does not iterate must refuse")
        assertEquals(
            unaddressed,
            prepared.tracked().addressedTargetOf("Claims/Amount", "/Policy", prepared.cursor("HolderRequired")),
            "the two arms must refuse the same unaddressed operand",
        )
    }
}
