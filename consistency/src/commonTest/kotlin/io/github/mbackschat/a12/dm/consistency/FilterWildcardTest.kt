package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The checker's half of "a `Having` filter reads one row, so it takes no wildcard".
 *
 * Mirrors `rulekit.validate.laws.AggregateSlotLawsTest.whichFilterGateReports` on the same order shape. The
 * rows that matter are the ones where A12 reports something else: the operand's OWN star is legal in the very
 * same call, a star on a non-repeatable group is answered by the landing-site class, and a filter that is
 * illegal for another reason draws that reason instead.
 */
class FilterWildcardTest {

    private val noWildcards = "MVK_NO_WILDCARDS_ALLOWED"

    @Test
    fun onlyAStarredRepeatableGroupInsideTheFilterIsReported() {
        val actual = mutableMapOf<String, List<String>>()
        actual["a starred repeatable group in the filter"] =
            codes("Sum(Items*/Count Having [Items*/Sku] == \\\"x\\\") > 0")
        actual["the same star under the OUTER marker"] =
            codes("Sum(Items*/Count Having [\$Items*/Sku] == \\\"x\\\") > 0")
        actual["another aggregate of the same family"] =
            codes("MaxValue(Items*/Count Having [Items*/Sku] == \\\"x\\\") > 0")
        actual["the unstarred filter — the operand's own star is legal"] =
            codes("Sum(Items*/Count Having [Items/Sku] == \\\"x\\\") > 0")
        actual["a star on a NON-repeatable group"] =
            codes("Sum(Items*/Count Having [Shipping*/City] == \\\"x\\\") > 0")
        actual["a filter naming nothing declared"] =
            codes("Sum(Items*/Count Having [Items*/Nowhere] == \\\"x\\\") > 0")
        actual["a filter carrying a nested CALL"] =
            codes("Sum(Items*/Count Having FieldFilled(Items*/Sku)) > 0")

        val expected = mutableMapOf<String, List<String>>()
        expected["a starred repeatable group in the filter"] = listOf(noWildcards)
        expected["the same star under the OUTER marker"] = listOf(noWildcards)
        expected["another aggregate of the same family"] = listOf(noWildcards)
        // The aggregate's operand stars the identical group in the identical call and is ADMITTED, which is
        // the whole reason this is a position family: nothing about the star itself distinguishes the two.
        expected["the unstarred filter — the operand's own star is legal"] = emptyList()
        // A12 draws MVK_INVALID_WILDCARD, the landing-site class, and it outruns every slot gate — so
        // reporting here would name the wrong defect on a correct rejection.
        expected["a star on a NON-repeatable group"] = emptyList()
        // MVK_INVALID_ENTITY, the reference family's; a star on an address that names nothing is not this.
        expected["a filter naming nothing declared"] = emptyList()
        // This family hands back no star for a call nested in a filter; the nested `FieldFilled` refuses its
        // own starred operand (`rulekit.validate.laws.GroupOperandRepetitionScopeLawsTest`).
        expected["a filter carrying a nested CALL"] = listOf("MVK_NO_WILDCARDS_ALLOWED")
        assertEquals(expected, actual, "Having filter wildcard")
    }

    /** Every code this family draws, which is the only thing these models are built to defect in. */
    private fun codes(aggregate: String): List<String> =
        checkRead(ModelSource(model(aggregate))).findings
            .filter { it.code == noWildcards }
            .map { it.code }

    /** An order with one repeatable group and one fixed group, so both landing sites have a carrier. */
    private fun model(aggregate: String): String = """
        {"header":{"id":"order","locales":[{"code":"en_US"}]},
         "content":{"modelRoot":{"rootGroups":[
          {"type":"Group","id":"g0","name":"Order","Group":{"elements":[
            {"type":"Field","id":"f0","name":"Quantity","Field":{"fieldType":{"type":"NumberType"}}},
            {"type":"Group","id":"g1","name":"Items","Group":{"repeatability":50,"elements":[
              {"type":"Field","id":"f1","name":"Sku","Field":{"fieldType":{"type":"StringType"}}},
              {"type":"Field","id":"f2","name":"Count","Field":{"fieldType":{"type":"NumberType"}}}
            ]}},
            {"type":"Group","id":"g2","name":"Shipping","Group":{"elements":[
              {"type":"Field","id":"f3","name":"City","Field":{"fieldType":{"type":"StringType"}}}
            ]}},
            {"type":"Rule","id":"r0","name":"Probe","Rule":{
              "errorCondition":"FieldFilled(Quantity) And $aggregate",
              "errorEntityRelPath":"../Quantity",
              "errorCode":"C0","errorMessage":[{"locale":"en_US","text":"x"}]}}
          ]}}
         ]}}}
    """.trimIndent()
}
