package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The checker's half of `FieldValuesNotUnique`'s operand-kind gate.
 *
 * Every row mirrors one measured against the real kernel by
 * `rulekit.validate.laws.FieldValuesNotUniqueKindGateLawsTest`. The rows that carry the file are the clock
 * and stamp kinds: they CLEAR this gate and are refused by the next one under a different code, so a family
 * that read the admitted set off the class name would name the wrong class on a correct rejection.
 */
class FieldValuesNotUniqueOperandKindTest {

    private fun codes(condition: String): List<String> = check(
        ModelSource(
            """
            {"header":{"id":"claim","locales":[{"code":"en_US"}]},
             "content":{"modelRoot":{"rootGroups":[
              {"type":"Group","id":"g0","name":"Claim","Group":{"elements":[
                {"type":"Field","id":"f0","name":"Reference","Field":{"fieldType":{"type":"StringType"}}},
                {"type":"Field","id":"f1","name":"Adjuster","Field":{"fieldType":{"type":"StringType"}}},
                {"type":"Field","id":"f2","name":"FiledOn","Field":{"fieldType":{"type":"DateType",
                  "DateType":{"format":"yyyy-MM-dd"}}}},
                {"type":"Field","id":"f3","name":"ClosedOn","Field":{"fieldType":{"type":"DateType",
                  "DateType":{"format":"yyyy-MM-dd"}}}},
                {"type":"Field","id":"f4","name":"SpelledOtherwise","Field":{"fieldType":{"type":"DateType",
                  "DateType":{"format":"dd.MM.yyyy"}}}},
                {"type":"Field","id":"f5","name":"SealedAt","Field":{"fieldType":{"type":"DateTimeType",
                  "DateTimeType":{"format":"yyyy-MM-dd'T'HH:mm:ss"}}}},
                {"type":"Field","id":"f6","name":"LoggedAt","Field":{"fieldType":{"type":"TimeType",
                  "TimeType":{"format":"HH:mm:ss"}}}},
                {"type":"Field","id":"f7","name":"CoverPeriod","Field":{"fieldType":{"type":"DateRangeType",
                  "DateRangeType":{"format":"yyyy-MM-dd","rangeSeparator":"/"}}}},
                {"type":"Rule","id":"r0","name":"Probe","Rule":{
                  "errorCondition":"CONDITION_HERE","errorEntityRelPath":"../Reference",
                  "errorCode":"C1","errorMessage":[{"locale":"en_US","text":"x"}]}}
              ]}}
             ]}}}
            """.trimIndent().replace("CONDITION_HERE", condition),
        ),
    ).findings.map { it.code }

    private fun subject(list: String): List<String> =
        codes("FieldFilled(Reference) And FieldValuesNotUnique($list)")

    @Test
    fun theKindsThisGateRefusesAndTheOnesItLeavesToTheNext() {
        val actual = mutableMapOf<String, List<String>>()
        actual["a date range"] = subject("CoverPeriod, Adjuster")
        actual["a TIME, which clears this gate"] = subject("LoggedAt, Adjuster")
        actual["a DATE-TIME, which clears this gate"] = subject("SealedAt, Adjuster")
        actual["two text fields"] = subject("Adjuster, Reference")
        actual["two dates spelled alike"] = subject("FiledOn, ClosedOn")

        val expected = mutableMapOf<String, List<String>>()
        expected["a date range"] = listOf("MVK_ONLY_STRING_ENUM_NUMBER_DATE_ALLOWED")
        // `FieldUtils.isDate` accepts the clock and stamp kinds, so A12 refuses these beside a text field
        // under the BASE-TYPE gate after this one. Reporting here would be the wrong class.
        expected["a TIME, which clears this gate"] = emptyList()
        expected["a DATE-TIME, which clears this gate"] = emptyList()
        expected["two text fields"] = emptyList()
        expected["two dates spelled alike"] = emptyList()
        assertEquals(expected, actual, "the kinds this gate refuses")
    }

    @Test
    fun theSpellingArmSpansTheTemporalKinds() {
        val actual = mutableMapOf<String, List<String>>()
        actual["one component set, two spellings"] = subject("FiledOn, SpelledOtherwise")
        actual["a date beside a date-time"] = subject("FiledOn, SealedAt")

        val expected = mutableMapOf<String, List<String>>()
        expected["one component set, two spellings"] = listOf("MVK_ONLY_STRING_ENUM_NUMBER_DATE_ALLOWED")
        expected["a date beside a date-time"] = listOf("MVK_ONLY_STRING_ENUM_NUMBER_DATE_ALLOWED")
        assertEquals(expected, actual, "the spelling arm spans the temporal kinds")
    }

    @Test
    fun theFindingNamesBothFieldsAndTheirSpellings() {
        val finding = check(
            ModelSource(
                """
                {"header":{"id":"claim","locales":[{"code":"en_US"}]},
                 "content":{"modelRoot":{"rootGroups":[
                  {"type":"Group","id":"g0","name":"Claim","Group":{"elements":[
                    {"type":"Field","id":"f0","name":"Reference","Field":{"fieldType":{"type":"StringType"}}},
                    {"type":"Field","id":"f2","name":"FiledOn","Field":{"fieldType":{"type":"DateType",
                      "DateType":{"format":"yyyy-MM-dd"}}}},
                    {"type":"Field","id":"f4","name":"SpelledOtherwise","Field":{"fieldType":{"type":"DateType",
                      "DateType":{"format":"dd.MM.yyyy"}}}},
                    {"type":"Rule","id":"r0","name":"Probe","Rule":{
                      "errorCondition":"FieldFilled(Reference) And FieldValuesNotUnique(FiledOn, SpelledOtherwise)",
                      "errorEntityRelPath":"../Reference",
                      "errorCode":"C1","errorMessage":[{"locale":"en_US","text":"x"}]}}
                  ]}}
                 ]}}}
                """.trimIndent(),
            ),
        ).findings.single { it.code == "MVK_ONLY_STRING_ENUM_NUMBER_DATE_ALLOWED" }

        assertTrue("yyyy-MM-dd" in finding.explain, finding.explain)
        assertTrue("dd.MM.yyyy" in finding.explain, finding.explain)
    }
}
