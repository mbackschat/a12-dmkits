package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The checker's half of what a date or date-range side of a comparison demands of its partner.
 *
 * Every row mirrors one measured against the real kernel by
 * `rulekit.validate.laws.TemporalComparisonAgreementLawsTest`. Three rows carry the risk: two formats of
 * different PRECISION are admitted, a tolerance comparison is the tolerance arm's alone, and a date range
 * under ordering is the operator gate's — naming this family's class on any of them would be the wrong class
 * on a correct rejection.
 */
class TemporalComparisonAgreementTest {

    private fun codes(condition: String): List<String> = check(
        ModelSource(
            """
            {"header":{"id":"policy","locales":[{"code":"en_US"}]},
             "content":{"modelRoot":{"rootGroups":[
              {"type":"Group","id":"g0","name":"Policy","Group":{"elements":[
                {"type":"Field","id":"f0","name":"Reference","Field":{"fieldType":{"type":"StringType"}}},
                {"type":"Field","id":"f1","name":"Premium","Field":{"fieldType":{"type":"NumberType",
                  "NumberType":{"maxFractionalDigits":2}}}},
                {"type":"Field","id":"f2","name":"Start","Field":{"fieldType":{"type":"DateType",
                  "DateType":{"format":"dd.MM.yyyy"}}}},
                {"type":"Field","id":"f3","name":"Finish","Field":{"fieldType":{"type":"DateType",
                  "DateType":{"format":"dd.MM.yyyy"}}}},
                {"type":"Field","id":"f4","name":"Anniversary","Field":{"fieldType":{"type":"DateType",
                  "DateType":{"format":"MM-dd"}}}},
                {"type":"Field","id":"f5","name":"ReportMonth","Field":{"fieldType":{"type":"DateType",
                  "DateType":{"format":"yyyy-MM"}}}},
                {"type":"Field","id":"f6","name":"Stamp","Field":{"fieldType":{"type":"DateTimeType",
                  "DateTimeType":{"format":"yyyy-MM-dd'T'HH:mm:ss"}}}},
                {"type":"Field","id":"f7","name":"Window","Field":{"fieldType":{"type":"DateRangeType",
                  "DateRangeType":{"format":"dd.MM.yyyy","rangeSeparator":"-"}}}},
                {"type":"Field","id":"f8","name":"Season","Field":{"fieldType":{"type":"DateRangeType",
                  "DateRangeType":{"format":"yyyy-MM-dd","rangeSeparator":"/"}}}},
                {"type":"Field","id":"f9","name":"MonthWindow","Field":{"fieldType":{"type":"DateRangeType",
                  "DateRangeType":{"format":"MM","rangeSeparator":"/"}}}},
                {"type":"Rule","id":"r0","name":"Probe","Rule":{
                  "errorCondition":"CONDITION_HERE","errorEntityRelPath":"../Reference",
                  "errorCode":"C1","errorMessage":[{"locale":"en_US","text":"x"}]}}
              ]}}
             ]}}}
            """.trimIndent().replace("CONDITION_HERE", condition),
        ),
    ).findings.map { it.code }

    private fun comparison(text: String): List<String> = codes("FieldFilled(Reference) And $text")

    private val date = listOf("MVK_INVALID_COMPARE_TO_DATE")

    private val range = listOf("MVK_INVALID_COMPARE_TO_DATE_RANGE")

    @Test
    fun whichFormatsADateComparisonAdmits() {
        val actual = mutableMapOf<String, List<String>>()
        actual["two identical formats"] = comparison("[Start] == [Finish]")
        actual["a year-bearing format against one without"] = comparison("[Start] == [Anniversary]")
        actual["two year-bearing formats of different PRECISION"] = comparison("[Start] == [ReportMonth]")
        actual["a date against a date-and-time"] = comparison("[Start] == [Stamp]")
        actual["the same two under ORDERING"] = comparison("[Start] < [Stamp]")

        val expected = mutableMapOf<String, List<String>>()
        expected["two identical formats"] = emptyList()
        expected["a year-bearing format against one without"] = date
        expected["two year-bearing formats of different PRECISION"] = emptyList()
        expected["a date against a date-and-time"] = date
        expected["the same two under ORDERING"] = emptyList()
        assertEquals(expected, actual, "which formats a date comparison admits")
    }

    @Test
    fun whatADatePartnerOfAnotherKindDraws() {
        val actual = mutableMapOf<String, List<String>>()
        actual["against a number"] = comparison("[Start] == [Premium]")
        actual["against a text"] = comparison("[Start] == [Reference]")
        actual["against a date RANGE"] = comparison("[Start] == [Window]")
        actual["a number on the LEFT of a date"] = comparison("[Premium] == [Start]")
        actual["a date ORDERED against a number"] = comparison("[Start] < [Premium]")
        actual["a number ORDERED against a date"] = comparison("[Premium] < [Start]")
        actual["a number CONSTANT ordered against a date"] = comparison("1 < [Start]")
        actual["a date ordered against a number constant"] = comparison("[Start] < 1")

        val expected = mutableMapOf<String, List<String>>()
        expected["against a number"] = date
        expected["against a text"] = date
        expected["against a date RANGE"] = date
        expected["a number on the LEFT of a date"] = date
        expected["a date ORDERED against a number"] = date
        expected["a number ORDERED against a date"] = date
        // A written-out number is typed as A12 types it, an orderable partner that is no date (the laws twin).
        expected["a number CONSTANT ordered against a date"] = date
        expected["a date ordered against a number constant"] = date
        assertEquals(expected, actual, "what a date partner of another kind draws")
    }

    @Test
    fun whatADateRangeComparisonDemands() {
        val actual = mutableMapOf<String, List<String>>()
        actual["two ranges of the same components, different format and separator"] =
            comparison("[Window] == [Season]")
        actual["two ranges of different components"] = comparison("[Window] == [MonthWindow]")
        actual["a range against a text"] = comparison("[Window] == [Reference]")
        actual["a range against a number"] = comparison("[Window] == [Premium]")

        val expected = mutableMapOf<String, List<String>>()
        expected["two ranges of the same components, different format and separator"] = emptyList()
        expected["two ranges of different components"] = range
        expected["a range against a text"] = range
        expected["a range against a number"] = range
        assertEquals(expected, actual, "what a date-range comparison demands")
    }

    /** The arms that answer ahead of this one — each a shape where reporting would be the wrong class. */
    @Test
    fun theArmsThisFamilyYieldsTo() {
        val actual = mutableMapOf<String, List<String>>()
        actual["a date ordered against a text"] = comparison("[Start] < [Reference]")
        actual["a date within a TOLERANCE of a number"] =
            comparison("[Premium] DiffersWithToleranceRange10 [Start]")
        actual["two ranges under ORDERING"] = comparison("[Window] < [Season]")

        val expected = mutableMapOf<String, List<String>>()
        expected["a date ordered against a text"] = listOf("MVK_INVALID_TYPE_FOR_COMPARISON")
        expected["a date within a TOLERANCE of a number"] = listOf("MVK_INVALID_TYPE_FOR_TOLERANCE_OP")
        expected["two ranges under ORDERING"] = listOf("MVK_INVALID_TYPE_FOR_COMPARISON")
        assertEquals(expected, actual, "the arms this family yields to")
    }
}
