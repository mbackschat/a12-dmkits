package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.consistency.GroupOperandCarriers.CARRIERS
import io.github.mbackschat.a12.dm.consistency.PolicyPathSource.RecoveriesIndex
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The checker's half of a keyed GROUP operand beside a keyed error field: every row mirrors one measured by
 * `rulekit.validate.laws.KeyedParallelGroupOperandLawsTest` ([KERNEL-FINDINGS KF371]).
 *
 * The admitted-parallel rows carry the weight: there the walk passes, and a checker that refused the parallel
 * level would reject a model A12 admits on every carrier.
 */
class KeyedParallelGroupOperandTest {

    private fun verdicts(
        index: RecoveriesIndex,
        carriers: List<String> = CARRIERS,
        errorField: String = "Recoveries/Note",
        parts: Boolean = false,
    ) = carriers.associateWith { carrier ->
        PolicyPathSource.rejections(
            "../$errorField", "FieldFilled($errorField) And $carrier",
            claimsKeyed = true, recoveries = index, parts = parts,
        )
    }

    private fun uniform(code: String, carriers: List<String> = CARRIERS) = carriers.associateWith { listOf(code) }

    @Test
    fun theWalksIndexClassesOnEveryCarrier() {
        val actual = linkedMapOf(
            "the fixed sibling, re-measured here" to verdicts(RecoveriesIndex.CLAIM_REF, errorField = "Insured/Remark"),
            "no index on the error field's chain" to verdicts(RecoveriesIndex.NONE),
            "two index fields of different names" to verdicts(RecoveriesIndex.OTHER_NAME),
            "one index name, two types" to verdicts(RecoveriesIndex.CLAIM_REF_AS_NUMBER),
        )
        val expected = linkedMapOf(
            "the fixed sibling, re-measured here" to uniform(NO_INDEX),
            "no index on the error field's chain" to uniform(NO_INDEX),
            "two index fields of different names" to uniform(INVALID_INDEX),
            "one index name, two types" to uniform(INVALID_INDEX_TYPE),
        )
        assertEquals(expected, actual, "the parallel level's index pair, on every group carrier")
    }

    @Test
    fun theKeyedLevelAboveAStarredGroupDrawsTheSameClasses() {
        val actual = RecoveriesIndex.entries.associateWith { verdicts(it, BELOW_A_STAR, parts = true) }
        val expected = mapOf(
            RecoveriesIndex.NONE to uniform(NO_INDEX, BELOW_A_STAR),
            RecoveriesIndex.CLAIM_REF to BELOW_A_STAR.associateWith { emptyList<String>() },
            RecoveriesIndex.OTHER_NAME to uniform(INVALID_INDEX, BELOW_A_STAR),
            RecoveriesIndex.CLAIM_REF_AS_NUMBER to uniform(INVALID_INDEX_TYPE, BELOW_A_STAR),
        )
        assertEquals(expected, actual, "the keyed level above a starred group")
    }

    /**
     * With one index name and type the walk admits the parallel level, and each carrier's own gate answers as
     * it does on a level the error field sits in — so a family that treated the joint level as unaddressed fell
     * silent on all six, and one that refused it would reject a model A12 admits on the rest.
     */
    @Test
    fun aLegalParallelIterationLeavesEachCarriersOwnGateToAnswer() {
        val expected = CARRIERS.associateWith { emptyList<String>() } + mapOf(
            "FieldFilled(Claims)" to listOf("MVK_NO_GROUPS_ALLOWED"),
            "FieldNotFilled(Claims)" to listOf("MVK_NO_GROUPS_ALLOWED"),
            "NumberOfFilledGroups(Claims) > 0" to listOf("MVK_PARAMSIZE_INVALIDGN"),
            "Sum(Claims, Insured) > 0" to listOf("MVK_NO_NUMBER"),
            // A12 draws MVK_VARYING_TYPES_NOT_ALLOWED here as it does on a group that does not repeat. Type
            // matching is the under-report `fieldListOperationOperandFindings` discloses, not this walk's.
            "FirstFilledValue(Claims, Insured) > 0" to emptyList(),
            "NumberOfValueInFields(0 In Claims) > 0" to listOf("MVK_NO_NUMBER"),
        )
        assertEquals(expected, verdicts(RecoveriesIndex.CLAIM_REF), "one index name and type")
    }

    /** `KeyedParallelGroupOperandLawsTest.anOperandOnTheJointLevelMeetsItsScalarSlotsOwnGate`. */
    @Test
    fun anOperandOnTheJointLevelMeetsItsScalarSlotsOwnGate() {
        val expected = mapOf(
            "0 < RoundUpValue(Claims/Amount, 2)" to emptyList(),
            "0 < RoundUpValue(Claims/Remark, 2)" to listOf("MVK_NO_NUMBER"),
            "0 < RoundUpValue(Insured/Remark, 2)" to listOf("MVK_NO_NUMBER"),
            "DifferenceInDays(Claims/Remark, Insured/Remark) > 0" to listOf("MVK_NO_DATE"),
            // Escaped once for the fixture's JSON.
            "FieldValueIncludedInValueList(Claims/Remark, \\\"x\\\")" to emptyList(),
            "FieldValueIncludedInValueList(Claims, \\\"x\\\")" to listOf("MVK_NO_GROUPS_ALLOWED"),
        )
        assertEquals(expected, verdicts(RecoveriesIndex.CLAIM_REF, expected.keys.toList()),
            "an operand on the jointly iterated level")
    }

    private companion object {
        const val NO_INDEX = "MVK_ERROR_FIELD_NO_INDEX"
        const val INVALID_INDEX = "MVK_ERROR_FIELD_INVALID_INDEX"
        const val INVALID_INDEX_TYPE = "MVK_ERROR_FIELD_INVALID_INDEX_TYPE"

        /** `KeyedParallelGroupOperandLawsTest.BELOW_A_STAR`: carriers crossing `Claims` unstarred to a starred `Parts`. */
        val BELOW_A_STAR = listOf(
            "AtLeastOneGroupFilled(Claims/Parts*)",
            "NoGroupFilled(Claims/Parts*)",
            "NumberOfFilledGroups(Claims/Parts*) > 0",
            "Sum(Claims/Parts*) > 0",
        )
    }
}
