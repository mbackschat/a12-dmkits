package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The checker's half of `rulekit.validate.laws.FilterBodyTextRepeatedOutsideLawsTest`: a filter body is set
 * aside by its POSITION, so a conjunct spelling the body exactly is still read.
 *
 * The last row is the one a reader matching the body's text cannot pass — it blanked the conjunct along with
 * the body and named nothing — and its span is asserted too, since blanking by position is also what keeps
 * an offset into the blanked text an offset into the author's.
 */
class FilterBodyTextRepeatedOutsideTest {

    /** Escaped once for the fixture's JSON; [authored] is the text A12 and the checker read. */
    private val filteredSum = """Sum(Items*/Count Having [Items/Sku] == \"X\") > 0"""

    private val bodyAsAConjunct = """[Items/Sku] == \"X\""""

    @Test
    fun aConjunctSpellingTheFilterBodyIsStillRead() {
        val actual = mutableMapOf<String, List<String>>()
        actual["the filter alone — the control"] = findings(filteredSum).map { it.code }
        actual["the body's text alone, outside any filter"] = findings(bodyAsAConjunct).map { it.code }
        actual["the filter, then its body's text outside it"] =
            findings("$filteredSum And $bodyAsAConjunct").map { it.code }

        val expected = mutableMapOf<String, List<String>>()
        expected["the filter alone — the control"] = emptyList()
        expected["the body's text alone, outside any filter"] = listOf("MVK_NO_WILDCARD")
        expected["the filter, then its body's text outside it"] = listOf("MVK_NO_WILDCARD")
        assertEquals(expected, actual, "whether a conjunct spelling the filter body is read")
    }

    @Test
    fun theReadIsLocatedAtTheConjunctNotAtTheBody() {
        val condition = "$filteredSum And $bodyAsAConjunct"
        val span = findings(condition).single().span
        val text = authored(conditionPrefix + condition)

        assertEquals("Items/Sku", span?.let { text.substring(it.start, it.end) })
        assertEquals(text.lastIndexOf("Items/Sku"), span?.start, "the conjunct's, not the body's: $span")
    }

    private val conditionPrefix = "FieldFilled(Quantity) And "

    private fun authored(escaped: String) = escaped.replace("\\\"", "\"")

    private fun findings(conjunct: String): List<Finding> = checkRead(
        ModelSource(
            """
            {"header":{"id":"order","locales":[{"code":"en_US"}]},
             "content":{"modelRoot":{"rootGroups":[
              {"type":"Group","id":"g0","name":"Order","Group":{"elements":[
                {"type":"Field","id":"f0","name":"Quantity","Field":{"fieldType":{"type":"NumberType"}}},
                {"type":"Group","id":"g1","name":"Items","Group":{"repeatability":50,"elements":[
                  {"type":"Field","id":"f1","name":"Sku","Field":{"fieldType":{"type":"StringType"}}},
                  {"type":"Field","id":"f2","name":"Count","Field":{"fieldType":{"type":"NumberType"}}}
                ]}},
                {"type":"Rule","id":"r0","name":"Probe","Rule":{
                  "errorCondition":"$conditionPrefix$conjunct","errorEntityRelPath":"../Quantity",
                  "errorCode":"C1","errorMessage":[{"locale":"en_US","text":"x"}]}}
              ]}}
             ]}}}
            """.trimIndent(),
        ),
    ).findings
}
