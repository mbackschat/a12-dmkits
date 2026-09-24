package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A path of up-steps alone is a reference: it names an ancestor group, and every class that path can draw is
 * drawn for it.
 *
 * Every row mirrors one measured against the real kernel by `rulekit.validate.laws.PathSegmentWalkLawsTest`
 * ([KERNEL-FINDINGS KF465]). The reference reader used to keep only a run that carried a NAME, so `..`,
 * `../..` and `/..` reached no family at all and the checker was silent on each rejection. The admitted group
 * operand is the control that reading the run as a reference invents nothing.
 */
class UpStepOnlyPathTest {

    private fun judged(rows: Map<String, List<String>>, inInsured: Boolean, errorField: String) =
        rows.keys.associateWith {
            PolicyPathSource.rejections("../$errorField", "FieldFilled($errorField) And $it", inInsured = inInsured)
        }

    @Test
    fun fromANestedRuleTheRunNamesTheRootGroup() {
        val rows = linkedMapOf(
            "FieldFilled(..)" to listOf("MVK_ROOT_GROUP_REFERENCED"),
            "AtLeastOneGroupFilled(..)" to emptyList(),
            "FieldFilled(..*)" to listOf(WILDCARD_REL),
            "AtLeastOneGroupFilled(..*)" to listOf(WILDCARD_REL),
            "FieldFilled(..*/..)" to listOf(WILDCARD_REL),
            "FieldFilled(../..)" to listOf(TOO_MANY),
            "AtLeastOneGroupFilled(../..)" to listOf(TOO_MANY),
            "FieldFilled(/..)" to listOf(TOKEN),
            "AtLeastOneGroupFilled(/../..)" to listOf(TOKEN),
        )
        assertEquals(rows, judged(rows, inInsured = true, errorField = "Remark"), "from a nested rule")
    }

    @Test
    fun fromARootLevelRuleTheRunLeavesTheModel() {
        val rows = linkedMapOf(
            "FieldFilled(..)" to listOf(TOO_MANY),
            "AtLeastOneGroupFilled(..*)" to listOf(TOO_MANY),
            "FieldFilled(../..)" to listOf(TOO_MANY),
            "FieldFilled(..*/..)" to listOf(TOO_MANY),
            "FieldFilled(/..*)" to listOf(TOKEN),
        )
        assertEquals(rows, judged(rows, inInsured = false, errorField = "Holder"), "from a root-level rule")
    }

    private companion object {
        const val WILDCARD_REL = "MVK_INVALID_WILDCARD_REL"
        const val TOO_MANY = "MVK_TOO_MANY_PARENT_GROUPS"
        const val TOKEN = "MVK_UNEXPECTED_TOKEN"
    }
}
