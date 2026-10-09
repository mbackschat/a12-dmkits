package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The checker's half of the temporal extractor gates.
 *
 * Every row mirrors one measured against the real kernel by `adapter.laws.CheckerTemporalExtractorAgreementTest`
 * (KF529), which also runs the full grid through this family.
 *
 * The rows that carry the file separate the three gates, which sit one behind the other: a partial precision
 * answers before the format is asked, a date constant before its format is, and a nested operation is read by the
 * format A12 registers for it rather than by what it is built from.
 */
class TemporalExtractorOperandsTest {

    private fun codes(condition: String, baseYear: Boolean = false): List<String> = checkRead(
        ModelSource(
            """
            {"header":{"id":"policy-extractors","locales":[{"code":"en_US"}]},
             "content":{"modelInfo":{BASE_YEAR"name":"policy"},"modelRoot":{"rootGroups":[
              {"type":"Group","id":"g0","name":"Policy","Group":{"elements":[
                {"type":"Field","id":"f0","name":"Reference","Field":{"fieldType":{"type":"StringType"}}},
                {"type":"Field","id":"f1","name":"IssuedOn","Field":{"fieldType":{"type":"DateType",
                  "DateType":{"format":"yyyy-MM-dd"}}}},
                {"type":"Field","id":"f2","name":"RenewsOn","Field":{"fieldType":{"type":"DateType",
                  "DateType":{"format":"MM-dd"}}}},
                {"type":"Field","id":"f3","name":"CutOff","Field":{"fieldType":{"type":"TimeType",
                  "TimeType":{"format":"HH:mm:ss"}}}},
                {"type":"Field","id":"f4","name":"SignedAt","Field":{"fieldType":{"type":"DateTimeType",
                  "DateTimeType":{"format":"yyyy-MM-dd'T'HH:mm:ss"}}}},
                {"type":"Field","id":"f5","name":"ApproxOn","Field":{"fieldType":{"type":"DateType",
                  "DateType":{"format":"yyyy-MM-dd","datePrecision":"DAY_OPTIONAL"}}}},
                {"type":"Field","id":"f6","name":"Season","Field":{"fieldType":{"type":"DateRangeType",
                  "DateRangeType":{"format":"MM-dd","rangeSeparator":"/"}}}},
                {"type":"Field","id":"f7","name":"SeasonFrom","Field":{"fieldType":{"type":"DateRangeType",
                  "DateRangeType":{"format":"MM-dd","rangeSeparator":"/","interpretationOfYear":"FROM"}}}},
                {"type":"Field","id":"f8","name":"Hour","Field":{"fieldType":{"type":"NumberType",
                  "NumberType":{"maxFractionalDigits":0,"minValue":"0","maxValue":"23"}}}},
                {"type":"Rule","id":"r0","name":"Probe","Rule":{
                  "errorCondition":"FieldFilled(Reference) And CONDITION_HERE","errorEntityRelPath":"../Reference",
                  "errorCode":"C1","errorMessage":[{"locale":"en_US","text":"x"}]}}
              ]}}
             ]}}}
            """.trimIndent()
                .replace("BASE_YEAR", if (baseYear) "\"baseYear\":\"2024\"," else "")
                .replace("CONDITION_HERE", condition),
        ),
    ).findings.map { it.code }

    private val wrongFormat = listOf("MVK_WRONG_DATE_FORMAT_FOR_OP")

    @Test
    fun aPartialPrecisionAndAConstantAnswerAheadOfTheFormat() {
        val actual = linkedMapOf(
            "the day of a day-optional date" to codes("DayFromDate(ApproxOn) == 1"),
            "the hour of a day-optional date" to codes("HoursFromTime(ApproxOn) == 1"),
            "the year of a day-optional date" to codes("YearFromDate(ApproxOn) == 2024"),
            "the year of a quoted date" to codes("YearFromDate(\\\"01.01.2024\\\") == 2024"),
            "the date of a quoted stamp" to codes("DateFromDateTime(\\\"01.01.2024T10:00:00\\\") == [IssuedOn]"),
            "the hour of a date" to codes("HoursFromTime(IssuedOn) == 1"),
        )
        assertEquals(linkedMapOf(
            "the day of a day-optional date" to listOf("MVK_INVALID_DATE_TYPE"),
            // The format lacks the hour too; the precision gate is asked first.
            "the hour of a day-optional date" to listOf("MVK_INVALID_DATE_TYPE"),
            "the year of a day-optional date" to emptyList(),
            "the year of a quoted date" to listOf("MVK_NO_CONST"),
            "the date of a quoted stamp" to emptyList(),
            "the hour of a date" to wrongFormat,
        ), actual)
    }

    @Test
    fun aBaseYearAnswersOnlyTheNumberExtractorsYear() {
        val actual = linkedMapOf(
            "the year of a year-less date" to (codes("YearFromDate(RenewsOn) == 2024") to
                codes("YearFromDate(RenewsOn) == 2024", baseYear = true)),
            "the year of a clock" to (codes("YearFromDate(CutOff) == 1970") to
                codes("YearFromDate(CutOff) == 1970", baseYear = true)),
            "the date of a full date" to (codes("DateFromDateTime(IssuedOn) == [IssuedOn]") to
                codes("DateFromDateTime(IssuedOn) == [IssuedOn]", baseYear = true)),
        )
        assertEquals(linkedMapOf(
            "the year of a year-less date" to (wrongFormat to emptyList()),
            "the year of a clock" to (wrongFormat to emptyList()),
            "the date of a full date" to (wrongFormat to wrongFormat),
        ), actual)
    }

    @Test
    fun aNestedOperationCarriesTheFormatA12RegistersForIt() {
        val actual = linkedMapOf(
            "the year of a built time" to codes("YearFromDate(Time(Hour)) == 1970"),
            "the hour of a recovered time" to codes("HoursFromTime(TimeFromDateTime(SignedAt)) == 1"),
            "the hour of a recovered date" to codes("HoursFromTime(DateFromDateTime(SignedAt)) == 1"),
            "the year of a shifted year-less date" to codes("YearFromDate(AddDays(RenewsOn, 1)) == 2024"),
            "the year of a year-less range's start" to codes("YearFromDate(StartOfDateRange(Season)) == 2024"),
            "the year of a FROM range's start" to codes("YearFromDate(StartOfDateRange(SeasonFrom)) == 2024"),
            "the hour of now" to codes("HoursFromTime(Now) == 1"),
            "the hour of today" to codes("HoursFromTime(Today) == 1"),
        )
        assertEquals(linkedMapOf(
            "the year of a built time" to wrongFormat,
            "the hour of a recovered time" to emptyList(),
            "the hour of a recovered date" to wrongFormat,
            "the year of a shifted year-less date" to wrongFormat,
            "the year of a year-less range's start" to wrongFormat,
            "the year of a FROM range's start" to emptyList(),
            "the hour of now" to emptyList(),
            "the hour of today" to wrongFormat,
        ), actual)
    }

    /**
     * The rows another family answers first: a slot defect, and a refused inner extraction. This family stays
     * silent on both, so each row carries only the earlier class.
     */
    @Test
    fun anEarlierGateKeepsItsOwnClass() {
        val actual = linkedMapOf(
            "the year of a number field" to codes("YearFromDate(Hour) == 2024"),
            "the year of a refused inner extraction" to codes("YearFromDate(DateFromDateTime(IssuedOn)) == 2024"),
        )
        assertEquals(linkedMapOf(
            "the year of a number field" to listOf("MVK_NO_DATE"),
            "the year of a refused inner extraction" to wrongFormat,
        ), actual)
    }
}
