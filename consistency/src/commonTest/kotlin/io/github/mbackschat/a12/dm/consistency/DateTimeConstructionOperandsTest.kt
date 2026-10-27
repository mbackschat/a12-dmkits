package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The checker's half of the `DateTime(date, time)` operand gates, so the JS checker is held to them as well as
 * the JVM one. Every row mirrors one measured against the real kernel by
 * `adapter.laws.CheckerDateTimeConstructionAgreementTest` (KF627), which also runs the full grid through this
 * family.
 *
 * The rows that carry the file separate the gates: the Date operand answers before the Time operand, a partial
 * precision only once the Date's format passes, and a Base Year supplies the Date's year and nothing else.
 */
class DateTimeConstructionOperandsTest {

    private fun codes(construction: String, baseYear: Boolean = false): List<String> = checkRead(
        ModelSource(
            """
            {"header":{"id":"policy-stamps","locales":[{"code":"en_US"}]},
             "content":{"modelInfo":{BASE_YEAR"name":"policy"},"modelRoot":{"rootGroups":[
              {"type":"Group","id":"g0","name":"Policy","Group":{"elements":[
                {"type":"Field","id":"f0","name":"Reference","Field":{"fieldType":{"type":"StringType"}}},
                {"type":"Field","id":"f1","name":"IssuedOn","Field":{"fieldType":{"type":"DateType",
                  "DateType":{"format":"yyyy-MM-dd"}}}},
                {"type":"Field","id":"f2","name":"RenewsOn","Field":{"fieldType":{"type":"DateType",
                  "DateType":{"format":"MM-dd"}}}},
                {"type":"Field","id":"f3","name":"BilledIn","Field":{"fieldType":{"type":"DateType",
                  "DateType":{"format":"yyyy-MM"}}}},
                {"type":"Field","id":"f4","name":"CutOff","Field":{"fieldType":{"type":"TimeType",
                  "TimeType":{"format":"HH:mm:ss"}}}},
                {"type":"Field","id":"f5","name":"SignedAt","Field":{"fieldType":{"type":"DateTimeType",
                  "DateTimeType":{"format":"yyyy-MM-dd'T'HH:mm:ss"}}}},
                {"type":"Field","id":"f6","name":"ApproxOn","Field":{"fieldType":{"type":"DateType",
                  "DateType":{"format":"yyyy-MM-dd","datePrecision":"MONTH_OPTIONAL"}}}},
                {"type":"Rule","id":"r0","name":"Probe","Rule":{
                  "errorCondition":"FieldFilled(Reference) And CONSTRUCTION == [SignedAt]","errorEntityRelPath":"../Reference",
                  "errorCode":"C1","errorMessage":[{"locale":"en_US","text":"x"}]}}
              ]}}
             ]}}}
            """.trimIndent()
                .replace("BASE_YEAR", if (baseYear) "\"baseYear\":\"2024\"," else "")
                .replace("CONSTRUCTION", construction),
        ),
    ).findings.map { it.code }

    @Test
    fun eachOperandIsJudgedInA12sOrder() {
        val wrongFormat = listOf("MVK_WRONG_DATE_FORMAT_FOR_OP")
        val partial = listOf("MVK_INVALID_DATE_TYPE")
        val actual: Map<String, List<String>> = linkedMapOf(
            "a date and a clock" to codes("DateTime(IssuedOn, CutOff)"),
            "a partial date" to codes("DateTime(ApproxOn, CutOff)"),
            "a partial date beside a date as the time" to codes("DateTime(ApproxOn, IssuedOn)"),
            "a date as the time" to codes("DateTime(IssuedOn, IssuedOn)"),
            "a year-less date" to codes("DateTime(RenewsOn, CutOff)"),
            "a year-less date under a Base Year" to codes("DateTime(RenewsOn, CutOff)", baseYear = true),
            "a month as the date" to codes("DateTime(BilledIn, CutOff)"),
            "a stamp as the date" to codes("DateTime(SignedAt, CutOff)"),
            "a stamp as the time" to codes("DateTime(IssuedOn, SignedAt)"),
            "a clock as the date" to codes("DateTime(CutOff, CutOff)"),
            "a quoted year-less date" to codes("DateTime(\\\"01.02.\\\", CutOff)"),
            "a nested stamp as the date" to codes("DateTime(DateTime(IssuedOn, CutOff), CutOff)"),
            "Today as the time" to codes("DateTime(IssuedOn, Today)"),
        )
        val expected: Map<String, List<String>> = linkedMapOf(
            "a date and a clock" to emptyList(),
            "a partial date" to partial,
            "a partial date beside a date as the time" to partial,
            "a date as the time" to wrongFormat,
            "a year-less date" to wrongFormat,
            "a year-less date under a Base Year" to emptyList(),
            "a month as the date" to emptyList(),
            "a stamp as the date" to wrongFormat,
            "a stamp as the time" to wrongFormat,
            "a clock as the date" to wrongFormat,
            "a quoted year-less date" to wrongFormat,
            "a nested stamp as the date" to wrongFormat,
            "Today as the time" to wrongFormat,
        )
        assertEquals(expected, actual)
    }
}
