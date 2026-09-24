package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A starred leading up-step answers at its own segment, ahead of anything a later segment would draw.
 *
 * Every row mirrors one measured against the real kernel by `rulekit.validate.laws.PathSegmentWalkLawsTest`
 * ([KERNEL-FINDINGS KF465]). A12 walks a path segment by segment ([KERNEL-FINDINGS KF356]), and the leading run
 * is no exception: `..*` answers `MVK_INVALID_WILDCARD_REL`, so a climb above the root one step later and a
 * `.*` suffix further on are never reached. The checker used to report both beside it, because the depth
 * class came from the whole path's resolution and the suffix family read a resolved path without asking
 * which segment's gate answered first.
 */
class LeadingUpStepPreemptionTest {

    private fun fromInsured(operand: String) =
        PolicyPathSource.rejections("../Remark", "FieldFilled(Remark) And AtLeastOneGroupFilled($operand)",
            inInsured = true)

    @Test
    fun aStarredLeadingUpStepAnswersBeforeALaterSegmentDoes() {
        val rows = linkedMapOf(
            "..*/../Amount" to listOf(WILDCARD_REL),
            "..*/..*/Nope" to listOf(WILDCARD_REL),
            "..*/Claims.*" to listOf(WILDCARD_REL),
            "..*/Claims.*/Amount" to listOf(WILDCARD_REL),
            "../../Amount" to listOf("MVK_TOO_MANY_PARENT_GROUPS"),
            "../Claims.*" to listOf("MVK_POINT_IN_A12_MODEL"),
        )
        assertEquals(rows, rows.keys.associateWith(::fromInsured), "a starred leading up-step")
    }

    private companion object {
        const val WILDCARD_REL = "MVK_INVALID_WILDCARD_REL"
    }
}
