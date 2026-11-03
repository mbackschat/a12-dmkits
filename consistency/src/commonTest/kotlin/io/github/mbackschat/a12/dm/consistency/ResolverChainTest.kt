package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * What the reference-addressing chain answers, on the fixture that separates its two outcomes.
 *
 * **It was an ARMS test and is now a behaviour one, which is the honest reading of a completed migration.**
 * While the estate was part-migrated these helpers had two carriers and the test compared them; with every
 * family behind the read barrier the untracked arms are gone, so an equality would compare one arm with
 * itself. What the arms comparison was standing on is still worth locking: the chain's own outcomes, measured
 * on one model whose two rules differ in exactly one thing.
 *
 * `addressedTargetOf` composes the resolver, the star reader, the declaration's iteration group (itself over
 * the relative resolver and the target-field group) and the iterated levels, so each case here is the whole
 * chain's answer rather than one member's.
 */
class ResolverChainTest {

    /**
     * Two rules over one repeatable claim list, differing only in where their error field sits.
     *
     * That difference is the instrument: `ClaimAmountRequired` iterates the claim list because its error
     * field sits inside it, and `HolderRequired` does not — so the same written operand is addressed for one
     * and unaddressed for the other, on ONE model.
     */
    private val policy = ModelSource(
        """
        {"header":{"modelVersion":"29.4.0","id":"policies","locales":[{"code":"en_US"}]},
         "content":{"modelInfo":{"name":"model"},"modelRoot":{"rootGroups":[
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

    private fun tracked(): TrackedFactBase {
        val admitted = admit(policy)
        assertIs<Admission.Admitted>(admitted, "the fixture must be admissible")
        return prepare(admitted).tracked()
    }

    private fun TrackedFactBase.rule(name: String) = declarations().single { it.path == "/Policy/$name" }

    @Test
    fun aStarLandsOnTheRepeatableGroupAndIsRefusedOnAField() {
        val tracked = tracked()

        assertEquals(
            setOf("/Policy/Claims"),
            tracked.starredLevelsOf("Claims*/Amount", "/Policy", tracked.rule("ClaimAmountRequired")),
            "the star must resolve to the claim list",
        )
        // A star on something that is not a repeatable group is another family's class entirely, reported as
        // null rather than as an empty set — the two are read differently by every caller.
        assertNull(
            tracked.starredLevelsOf("Holder*", "/Policy", tracked.rule("HolderRequired")),
            "a star on a field must refuse, not answer with no levels",
        )
    }

    @Test
    fun aDeclarationIteratesWhereItsErrorFieldSits() {
        val tracked = tracked()

        // Also the case that exercises `groupOfTargetField` and the relative resolver, which no other test
        // reaches: a wrong answer from either lands here.
        assertEquals(
            "/Policy/Claims",
            tracked.iterationGroupOf(tracked.rule("ClaimAmountRequired")),
            "the rule's error field sits in the claim list, so that is the level it iterates",
        )
        assertEquals(
            "/Policy",
            tracked.iterationGroupOf(tracked.rule("HolderRequired")),
            "a rule whose error field sits at the policy iterates nothing below it",
        )
    }

    @Test
    fun anOperandIsAddressedByItsStarOrByTheDeclarationsOwnIteration() {
        val tracked = tracked()

        assertEquals(
            "/Policy/Claims/Amount",
            tracked.addressedTargetOf("Claims*/Amount", "/Policy", tracked.rule("ClaimAmountRequired"), false, false),
            "a starred operand addresses the repetition it names",
        )
        // UNSTARRED, on the rule that DOES iterate the claim list — the one case that separates the
        // declaration's iteration group from its declaring group, and the case a mutant found missing: with a
        // star present the level is addressed either way. A12 admits this operand, so a null here is a family
        // going silent on a model it should judge.
        assertEquals(
            "/Policy/Claims/Amount",
            tracked.addressedTargetOf("Claims/Amount", "/Policy", tracked.rule("ClaimAmountRequired"), false, false),
            "the rule's own iteration addresses the repetition without a star",
        )
        assertNull(
            tracked.addressedTargetOf("Claims/Amount", "/Policy", tracked.rule("HolderRequired"), false, false),
            "an unstarred crossing of a repetition the declaration does not iterate must refuse",
        )
    }

    @Test
    fun aBareSpecReducesToItsFieldAndAnExpressionReducesToNothing() {
        val tracked = tracked()

        // Unstarred deliberately: a starred spelling is not a bare entity spec at all
        // (`BareEntitySpecStarTest`), so it would answer null for a different reason.
        assertEquals(
            "/Policy/Claims/Amount",
            tracked.bareReferenceField("Claims/Amount", "/Policy", tracked.rule("ClaimAmountRequired"), inAFilterBody = false)?.path,
            "the bare spec must reduce to the claim amount",
        )
        assertNull(
            tracked.bareReferenceField("{[Claims/Amount] + 1}", "/Policy", tracked.rule("ClaimAmountRequired"), inAFilterBody = false),
            "an expression must not reduce to the field inside it, whose type says nothing about the whole",
        )
    }
}
