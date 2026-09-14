package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The checker's half of A12's three-way split over the group-condition operators.
 *
 * Every row mirrors one measured against the real kernel by
 * `rulekit.validate.laws.GroupQuantifierWildcardLawsTest`. The rows that matter are the two operators A12
 * leaves ungated: they are spelled like the three that refuse and take the same operand list, so a family
 * built from the operators' names would over-reject them.
 */
class GroupConditionWildcardTest {

    private val scalar = "MVK_NO_WILDCARDS_ALLOWED"
    private val list = "MVK_NO_WILDCARDS_G_ALLOWED"

    @Test
    fun theStarIsRefusedByFiveOfTheSevenGroupConditions() {
        val actual = mutableMapOf<String, List<String>>()
        actual["GroupFilled"] = codes("GroupFilled(Lines*)")
        actual["GroupNotFilled"] = codes("GroupNotFilled(Lines*)")
        actual["AllGroupsFilled"] = codes("AllGroupsFilled(Lines*, Shipping)")
        actual["GroupsNotCollectivelyFilled"] = codes("GroupsNotCollectivelyFilled(Lines*, Shipping)")
        actual["NotAllGroupsFilled"] = codes("NotAllGroupsFilled(Lines*, Shipping)")
        actual["AtLeastOneGroupFilled"] = codes("AtLeastOneGroupFilled(Lines*, Shipping)")
        actual["NoGroupFilled"] = codes("NoGroupFilled(Lines*, Shipping)")
        actual["NumberOfFilledGroups"] = codes("NumberOfFilledGroups(Lines*) > 0")

        val expected = mutableMapOf<String, List<String>>()
        expected["GroupFilled"] = listOf(scalar)
        expected["GroupNotFilled"] = listOf(scalar)
        expected["AllGroupsFilled"] = listOf(list)
        expected["GroupsNotCollectivelyFilled"] = listOf(list)
        expected["NotAllGroupsFilled"] = listOf(list)
        // A12 runs no wildcard gate for these three and admits the identical star.
        expected["AtLeastOneGroupFilled"] = emptyList()
        expected["NoGroupFilled"] = emptyList()
        expected["NumberOfFilledGroups"] = emptyList()
        assertEquals(expected, actual, "which group conditions bar a repetition wildcard")
    }

    @Test
    fun whereTheStarHasToLandForThisFamilyToSpeak() {
        val actual = mutableMapOf<String, List<String>>()
        actual["a FIELD operand below the starred level"] = codes("AllGroupsFilled(Lines*/Sku, Shipping)")
        actual["two starred levels"] = codes("AllGroupsFilled(Lines*/Discounts*, Shipping)")
        actual["a single starred operand"] = codes("AllGroupsFilled(Lines*)")
        actual["nested inside a Having filter"] =
            codes("Sum(Lines*/Quantity Having AllGroupsFilled(Lines*, Shipping)) > 0")
        actual["a star on a NON-repeatable group"] = codes("AllGroupsFilled(Shipping*, Billing)")
        actual["a reference whose leaf names nothing"] = codes("AllGroupsFilled(Lines*/Nowhere, Shipping)")
        actual["the unstarred spelling — the control"] = codes("AllGroupsFilled(Billing, Shipping)")

        val expected = mutableMapOf<String, List<String>>()
        // A field operand does NOT escape, though the kernel's own predicate exempts a field's own
        // repetition index — measured, and the reason this family tests the reference and not the kind.
        expected["a FIELD operand below the starred level"] = listOf(list)
        expected["two starred levels"] = listOf(list)
        expected["a single starred operand"] = listOf(list)
        // The class is one no filter gate can produce, so the quantifier's own gate demonstrably answers.
        expected["nested inside a Having filter"] = listOf(list)
        // MVK_INVALID_WILDCARD, the landing-site class, outruns every slot gate — another family's row.
        expected["a star on a NON-repeatable group"] = emptyList()
        // Unmeasured in this position, so silence rather than a class resting on a reading.
        expected["a reference whose leaf names nothing"] = emptyList()
        expected["the unstarred spelling — the control"] = emptyList()
        assertEquals(expected, actual, "where the star has to land")
    }

    /** Only this family's two codes, so another family's finding cannot make a row look right. */
    private fun codes(condition: String): List<String> =
        check(ModelSource(model(condition))).findings
            .filter { it.code == scalar || it.code == list }
            .map { it.code }

    /** An order with a nested repeatable group and two fixed ones, so every landing site has a carrier. */
    private fun model(condition: String): String = """
        {"header":{"id":"order","locales":[{"code":"en_US"}]},
         "content":{"modelRoot":{"rootGroups":[
          {"type":"Group","id":"g0","name":"Order","Group":{"elements":[
            {"type":"Field","id":"f0","name":"Reference","Field":{"fieldType":{"type":"StringType"}}},
            {"type":"Group","id":"g1","name":"Lines","Group":{"repeatability":50,"elements":[
              {"type":"Field","id":"f1","name":"Sku","Field":{"fieldType":{"type":"StringType"}}},
              {"type":"Field","id":"f2","name":"Quantity","Field":{"fieldType":{"type":"NumberType"}}},
              {"type":"Group","id":"g2","name":"Discounts","Group":{"repeatability":5,"elements":[
                {"type":"Field","id":"f3","name":"Code","Field":{"fieldType":{"type":"StringType"}}}
              ]}}
            ]}},
            {"type":"Group","id":"g3","name":"Shipping","Group":{"elements":[
              {"type":"Field","id":"f4","name":"Method","Field":{"fieldType":{"type":"StringType"}}}
            ]}},
            {"type":"Group","id":"g4","name":"Billing","Group":{"elements":[
              {"type":"Field","id":"f5","name":"Account","Field":{"fieldType":{"type":"StringType"}}}
            ]}},
            {"type":"Rule","id":"r0","name":"Probe","Rule":{
              "errorCondition":"FieldFilled(Reference) And $condition",
              "errorEntityRelPath":"../Reference",
              "errorCode":"C0","errorMessage":[{"locale":"en_US","text":"x"}]}}
          ]}}
         ]}}}
    """.trimIndent()
}
