package io.github.mbackschat.a12.dm.predicates

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The reach law both reference checks consume, held to the shapes its two former copies had to agree on. The
 * kernel verdicts behind the rows are `rulekit.dsl.RuleGroupErrorEntityTest` (a group target) and
 * `validate.laws.ErrorFieldReferenceReachLawsTest` (an enclosing group); the segment-boundary row is the one a
 * bare `startsWith` gets wrong.
 */
class ReferenceReachPredicatesTest {

    @Test
    fun whichReferencesReachATarget() {
        val actual = linkedMapOf(
            "the target itself" to referenceReaches("/Order/Items/Sku", "/Order/Items/Sku"),
            "its group" to referenceReaches("/Order/Items", "/Order/Items/Sku"),
            "the root above it" to referenceReaches("/Order", "/Order/Items/Parts"),
            "a field inside a target group" to referenceReaches("/Order/Items/Parts/PartNo", "/Order/Items/Parts"),
            "a sibling" to referenceReaches("/Order/Items/Note", "/Order/Items/Sku"),
            "a name prefix that is not a segment" to referenceReaches("/Order/Item", "/Order/Items/Sku"),
        )
        assertEquals(linkedMapOf(
            "the target itself" to true,
            "its group" to true,
            "the root above it" to true,
            "a field inside a target group" to false,
            "a sibling" to false,
            "a name prefix that is not a segment" to false,
        ), actual)
    }
}
