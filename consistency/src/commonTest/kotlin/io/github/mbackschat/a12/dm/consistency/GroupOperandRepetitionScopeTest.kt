package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.consistency.GroupOperandCarriers.CARRIERS
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The checker's half of the operand-scope walk on a GROUP operand, and of the gate order in the two scalar
 * field slots.
 *
 * Every row mirrors one measured against the real kernel by
 * `rulekit.validate.laws.GroupOperandRepetitionScopeLawsTest` ([KERNEL-FINDINGS KF371], [KERNEL-FINDINGS
 * KF357]). The walk used to skip a reference naming a group, on the reading that each group carrier gates it
 * first; none does, so an unstarred repeatable group was left unreported on every carrier.
 */
class GroupOperandRepetitionScopeTest {

    private fun fromRoot(carrier: String) =
        PolicyPathSource.rejections("../Holder", "FieldFilled(Holder) And $carrier")

    private fun fromInsured(carrier: String) =
        PolicyPathSource.rejections("../Remark", "FieldFilled(Remark) And $carrier", inInsured = true)

    /** A rule declared at the root reporting on `Insured/Remark`, so `Claims` is a parallel level. */
    private fun parallelFromRoot(carrier: String, claimsKeyed: Boolean) = PolicyPathSource.rejections(
        "../Insured/Remark", "FieldFilled(Insured/Remark) And $carrier", claimsKeyed = claimsKeyed,
    )

    @Test
    fun anUnstarredRepeatableGroupIsRefusedByTheWalkOnEveryCarrier() {
        val rows = CARRIERS.associateWith { listOf(NO_WILDCARD) }
        assertEquals(rows, rows.keys.associateWith(::fromRoot), "an unstarred repeatable group, from the root")
    }

    @Test
    fun anIndexOnALevelBelowTheErrorFieldsGroupMovesNothing() {
        val rows = CARRIERS.associateWith { listOf(NO_WILDCARD) }
        val actual = rows.keys.associateWith {
            PolicyPathSource.rejections("../Holder", "FieldFilled(Holder) And $it", claimsKeyed = true)
        }
        assertEquals(rows, actual, "an index-keyed group, from the root")
    }

    @Test
    fun aParallelLevelDrawsTheMissingIndexOnEveryCarrierWhereItDeclaresOne() {
        val unkeyed = CARRIERS.associateWith { listOf(NO_WILDCARD) }
        assertEquals(unkeyed, CARRIERS.associateWith { parallelFromRoot(it, claimsKeyed = false) }, "no index")
        val keyed = CARRIERS.associateWith { listOf("MVK_ERROR_FIELD_NO_INDEX") }
        assertEquals(keyed, CARRIERS.associateWith { parallelFromRoot(it, claimsKeyed = true) }, "keyed")
    }

    @Test
    fun theNonRepeatingTwinsPassTheWalk() {
        val rows = linkedMapOf(
            "GroupFilled(Insured)" to emptyList(),
            "AllFieldsFilled(Insured, Broker)" to emptyList(),
            "NotAllGroupsFilled(Insured, Broker)" to emptyList(),
            "Sum(Insured) > 0" to listOf("MVK_NO_NUMBER"),
        )
        assertEquals(rows, rows.keys.associateWith(::fromRoot), "a group that does not repeat")
    }

    @Test
    fun aFilteredGroupIsRefusedAsAGroupBeforeTheWalk() {
        val rows = listOf(
            "AtLeastOneFieldFilled(Claims Having [Claims/Amount] > 0)",
            "AtLeastOneFieldFilled(Claims* Having [Claims/Amount] > 0)",
            "Sum(Claims Having [Claims/Amount] > 0) > 0",
        ).associateWith { listOf(NO_GROUPS) }
        assertEquals(rows, rows.keys.associateWith(::fromRoot), "a filtered group")
    }

    @Test
    fun aParallelRepeatableGroupWithoutAnIndexIsRefusedToo() {
        val rows = listOf(
            "FieldFilled(../Claims)", "GroupFilled(../Claims)", "AtLeastOneGroupFilled(../Claims)",
            "NumberOfFilledGroups(../Claims) > 0",
        ).associateWith { listOf(NO_WILDCARD) }
        assertEquals(rows, rows.keys.associateWith(::fromInsured), "from a sibling group")
    }

    @Test
    fun aParallelRepeatableGroupWithAnIndexDrawsTheMissingIndexClass() {
        val rows = listOf(
            "FieldFilled(../Claims)", "GroupFilled(../Claims)", "AtLeastOneGroupFilled(../Claims)",
            "NumberOfFilledGroups(../Claims) > 0",
        ).associateWith { listOf("MVK_ERROR_FIELD_NO_INDEX") }
        val actual = rows.keys.associateWith {
            PolicyPathSource.rejections("../Remark", "FieldFilled(Remark) And $it", inInsured = true, claimsKeyed = true)
        }
        assertEquals(rows, actual, "from a sibling group, Claims index-keyed")
    }

    @Test
    fun theScalarFieldSlotsRefuseAGroupThenAStar() {
        val rows = linkedMapOf(
            "FieldFilled(Claims*)" to listOf(NO_GROUPS),
            "FieldNotFilled(Claims*)" to listOf(NO_GROUPS),
            "FieldFilled(Insured)" to listOf(NO_GROUPS),
            "FieldFilled(Claims*/Amount)" to listOf(NO_WILDCARDS),
            "FieldNotFilled(Claims*/Amount)" to listOf(NO_WILDCARDS),
            "FieldFilled(Claims/Amount)" to listOf(NO_WILDCARD),
            "FieldFilled(Claims.*)" to listOf("MVK_POINT_IN_A12_MODEL"),
            "FieldFilled(Claims*/Amount*)" to listOf("MVK_INVALID_WILDCARD"),
            "FieldFilled(Insured*/Age)" to listOf("MVK_INVALID_WILDCARD"),
        )
        assertEquals(rows, rows.keys.associateWith(::fromRoot), "a scalar field slot, from the root")
        val nested = linkedMapOf(
            "FieldFilled(../Claims*/Amount)" to listOf(NO_WILDCARDS),
            "FieldFilled(..*/Claims)" to listOf("MVK_INVALID_WILDCARD_REL"),
        )
        assertEquals(nested, nested.keys.associateWith(::fromInsured), "a scalar field slot, nested")
    }

    private companion object {
        const val NO_WILDCARD = "MVK_NO_WILDCARD"
        const val NO_GROUPS = "MVK_NO_GROUPS_ALLOWED"
        const val NO_WILDCARDS = "MVK_NO_WILDCARDS_ALLOWED"
    }
}
