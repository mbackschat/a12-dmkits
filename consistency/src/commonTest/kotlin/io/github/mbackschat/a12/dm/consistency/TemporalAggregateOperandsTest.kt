package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The checker's half of what an entity-list aggregate's temporal operands must share.
 *
 * Every row mirrors one measured against the real kernel by
 * `rulekit.validate.laws.TemporalAggregateComponentSetLawsTest` on a motor-claims carrier; this one is a
 * policy carrier, so a row that agrees agrees on two shapes. The separating rows are the ones whose component
 * SETS are equal and whose format SPELLINGS differ — without them the table would be satisfied by a reader
 * that compared the strings, which is what the neighbouring `FieldValuesNotUnique` gate really does.
 */
class TemporalAggregateOperandsTest {

    private val incompatible = "MVK_DATEFORMATS_NOT_COMPATIBLE"

    private val partialRefused = "MVK_PARTIAL_DATE_NOT_ALLOWED"

    /** A policy carrier declaring, per component set, two spellings plus a same-spelling control. */
    private fun codes(condition: String, baseYear: Boolean = false): List<String> {
        val info = if (baseYear) """"modelInfo":{"baseYear":"2024"},""" else ""
        return checkRead(
            ModelSource(
                """
            {"header":{"id":"policy","locales":[{"code":"en_US"}]},
             "content":{$info"modelRoot":{"rootGroups":[
              {"type":"Group","id":"g0","name":"Policy","Group":{"elements":[
                {"type":"Field","id":"f0","name":"Reference","Field":{"fieldType":{"type":"StringType"}}},
                {"type":"Field","id":"f1","name":"Premium","Field":{"fieldType":{"type":"NumberType"}}},
                ${dateField("f2", "StartOn", "yyyy-MM-dd")},
                ${dateField("f3", "EndOn", "dd.MM.yyyy")},
                ${dateField("f4", "AuditOn", "yyyy-MM-dd")},
                ${dateField("f5", "PeriodFrom", "yyyy-MM")},
                ${dateField("f6", "PeriodTo", "yyyyMM")},
                ${dateField("f7", "RenewalMonth", "MM")},
                ${dateField("f8", "Anniversary", "MM-dd")},
                ${dateField("f9", "UwYear", "yyyy")},
                {"type":"Field","id":"f10","name":"ReportYear","Field":{"fieldType":{
                  "type":"DateFragmentType","DateFragmentType":{"formatOfFragment":"yyyy"}}}},
                {"type":"Field","id":"f11","name":"DatedShift","Field":{"fieldType":{
                  "type":"TimeType","TimeType":{"format":"yyyy-MM-dd"}}}},
                {"type":"Field","id":"f12","name":"DatedStamp","Field":{"fieldType":{
                  "type":"DateTimeType","DateTimeType":{"format":"yyyy-MM-dd"}}}},
                {"type":"Field","id":"f13","name":"ClockShift","Field":{"fieldType":{
                  "type":"TimeType","TimeType":{"format":"HH:mm:ss"}}}},
                {"type":"Field","id":"f14","name":"ClockAudit","Field":{"fieldType":{
                  "type":"TimeType","TimeType":{"format":"HH:mm:ss"}}}},
                {"type":"Field","id":"f15","name":"StampSealed","Field":{"fieldType":{
                  "type":"DateTimeType","DateTimeType":{"format":"yyyy-MM-dd'T'HH:mm:ss"}}}},
                {"type":"Field","id":"f16","name":"NotifiedOn","Field":{"fieldType":{"type":"DateType",
                  "DateType":{"format":"yyyy-MM-dd","datePrecision":"DAY_OPTIONAL"}}}},
                {"type":"Field","id":"f21","name":"OnsetOn","Field":{"fieldType":{"type":"DateType",
                  "DateType":{"format":"yyyy-MM-dd","datePrecision":"MONTH_OPTIONAL"}}}},
                {"type":"Field","id":"f22","name":"CoverStart","Field":{"fieldType":{"type":"DateType",
                  "DateType":{"format":"yyyy-MM-dd","datePrecision":"YEAR_OPTIONAL"}}}},
                {"type":"Group","id":"g3","name":"Assessments","Group":{"elements":[
                  ${dateField("f23", "AssessedOn", "yyyy-MM-dd")},
                  {"type":"Field","id":"f24","name":"EstimatedOn","Field":{"fieldType":{"type":"DateType",
                    "DateType":{"format":"yyyy-MM-dd","datePrecision":"MONTH_OPTIONAL"}}}}
                ]}},
                {"type":"Group","id":"g1","name":"Window","Group":{"elements":[
                  ${dateField("f17", "OpenedOn", "yyyy-MM-dd")},
                  ${dateField("f18", "ShutOn", "dd.MM.yyyy")}
                ]}},
                {"type":"Group","id":"g2","name":"Spread","Group":{"elements":[
                  ${dateField("f19", "SpreadFrom", "yyyy-MM-dd")},
                  ${dateField("f20", "SpreadTo", "yyyy-MM")}
                ]}},
                {"type":"Rule","id":"r0","name":"Probe","Rule":{
                  "errorCondition":"FieldFilled(Reference) And $condition",
                  "errorEntityRelPath":"../Reference","errorCode":"C1",
                  "errorMessage":[{"locale":"en_US","text":"x"}]}}
              ]}}
             ]}}}
                """.trimIndent(),
            ),
        ).findings.map { it.code }
    }

    private fun dateField(id: String, name: String, format: String) =
        """{"type":"Field","id":"$id","name":"$name","Field":{"fieldType":{"type":"DateType",""" +
            """"DateType":{"format":"$format"}}}}"""

    /** What the gate reads: the components a format spells, never the spelling. */
    @Test
    fun theGateReadsComponentsAndNotSpelling() {
        val actual = mutableMapOf<String, List<String>>()
        actual["two spellings of one complete-date set"] = codes("MaxValue(StartOn, EndOn) == [AuditOn]")
        actual["one spelling of the same set — the control"] = codes("MaxValue(StartOn, AuditOn) == [EndOn]")
        actual["two spellings of one year-month set"] = codes("MinValue(PeriodFrom, PeriodTo) == [PeriodFrom]")
        actual["a complete date beside a year-month"] = codes("MaxValue(StartOn, PeriodFrom) == [AuditOn]")
        actual["the count carrier over the same disagreement"] =
            codes("NumberOfDifferentValues(StartOn, PeriodFrom) > 1")

        val expected = mutableMapOf<String, List<String>>()
        expected["two spellings of one complete-date set"] = emptyList()
        expected["one spelling of the same set — the control"] = emptyList()
        expected["two spellings of one year-month set"] = emptyList()
        expected["a complete date beside a year-month"] = listOf(incompatible)
        expected["the count carrier over the same disagreement"] = listOf(incompatible)
        assertEquals(expected, actual, "the gate reads components and not spelling")
    }

    /** …and it reads the DECLARATION's components, not the field KIND's — the pairs that separate the two. */
    @Test
    fun theGateReadsTheDeclarationAndNotTheKind() {
        val actual = mutableMapOf<String, List<String>>()
        actual["a date-declared TIME beside a DATE"] = codes("MaxValue(DatedShift, StartOn) == [AuditOn]")
        actual["a date-declared DATE_TIME beside a DATE"] = codes("MaxValue(DatedStamp, StartOn) == [AuditOn]")
        actual["a clock-declared TIME beside a DATE"] = codes("MaxValue(ClockShift, StartOn) == [AuditOn]")
        actual["a stamp-declared DATE_TIME beside a DATE"] = codes("MaxValue(StampSealed, StartOn) == [AuditOn]")
        actual["a DATE beside a DATE_FRAGMENT of one set"] = codes("MaxValue(UwYear, ReportYear) == [UwYear]")
        actual["two clock-declared TIMEs"] = codes("MaxValue(ClockShift, ClockAudit) == [ClockShift]")

        val expected = mutableMapOf<String, List<String>>()
        expected["a date-declared TIME beside a DATE"] = emptyList()
        expected["a date-declared DATE_TIME beside a DATE"] = emptyList()
        expected["a clock-declared TIME beside a DATE"] = listOf(incompatible)
        expected["a stamp-declared DATE_TIME beside a DATE"] = listOf(incompatible)
        expected["a DATE beside a DATE_FRAGMENT of one set"] = emptyList()
        expected["two clock-declared TIMEs"] = emptyList()
        assertEquals(expected, actual, "the gate reads the declaration and not the kind")
    }

    /** A declared base year SUPPLEMENTS the year component — it does not disable the gate. */
    @Test
    fun aBaseYearSupplementsTheYearComponent() {
        val actual = mutableMapOf<String, List<String>>()
        actual["a yearless month beside a year-month — unconfigured"] =
            codes("MaxValue(RenewalMonth, PeriodFrom) == [PeriodTo]")
        actual["a yearless month beside a year-month — with a base year"] =
            codes("MaxValue(RenewalMonth, PeriodFrom) == [PeriodTo]", baseYear = true)
        actual["a yearless month-day beside a complete date — with a base year"] =
            codes("MaxValue(Anniversary, StartOn) == [AuditOn]", baseYear = true)
        actual["the same month against the YEAR-only operand — the supplement's control"] =
            codes("MaxValue(RenewalMonth, UwYear) == [PeriodTo]", baseYear = true)
        actual["two year-bearing operands that genuinely differ — with a base year"] =
            codes("MaxValue(StartOn, PeriodFrom) == [AuditOn]", baseYear = true)

        val expected = mutableMapOf<String, List<String>>()
        expected["a yearless month beside a year-month — unconfigured"] = listOf(incompatible)
        expected["a yearless month beside a year-month — with a base year"] = emptyList()
        expected["a yearless month-day beside a complete date — with a base year"] = emptyList()
        expected["the same month against the YEAR-only operand — the supplement's control"] =
            listOf(incompatible)
        expected["two year-bearing operands that genuinely differ — with a base year"] = listOf(incompatible)
        assertEquals(expected, actual, "a base year supplements the year component")
    }


    /**
     * The chain's other reported class: these three carriers compare a DECODED date and a partial has none.
     *
     * The full-precision control is what keeps the refusals about the precision rather than about the model,
     * and the group row is the one a leader-keyed reading gets wrong — `Assessments` declares its full date
     * first and is refused anyway.
     */
    @Test
    fun everyPartialPrecisionIsRefusedWhateverTheFormatSpells() {
        val actual = mutableMapOf<String, List<String>>()
        actual["MaxValue over a DAY_OPTIONAL operand, declared SECOND"] =
            codes("MaxValue(StartOn, NotifiedOn) == [AuditOn]")
        actual["MinValue over a MONTH_OPTIONAL operand"] = codes("MinValue(OnsetOn, StartOn) == [AuditOn]")
        actual["the count carrier over a YEAR_OPTIONAL operand"] =
            codes("NumberOfDifferentValues(CoverStart, StartOn) > 1")
        actual["a partial LEADER, which has no leader to compare against"] =
            codes("MaxValue(NotifiedOn, StartOn) == [AuditOn]")
        actual["a group whose expansion holds one partial, declared SECOND"] =
            codes("MaxValue(Assessments) == [AuditOn]")
        actual["the full-precision control on the same carrier"] =
            codes("MaxValue(StartOn, AuditOn) == [EndOn]")

        val expected = mutableMapOf<String, List<String>>()
        expected["MaxValue over a DAY_OPTIONAL operand, declared SECOND"] = listOf(partialRefused)
        expected["MinValue over a MONTH_OPTIONAL operand"] = listOf(partialRefused)
        expected["the count carrier over a YEAR_OPTIONAL operand"] = listOf(partialRefused)
        expected["a partial LEADER, which has no leader to compare against"] = listOf(partialRefused)
        expected["a group whose expansion holds one partial, declared SECOND"] = listOf(partialRefused)
        expected["the full-precision control on the same carrier"] = emptyList()
        assertEquals(expected, actual, "every partial precision is refused whatever the format spells")
    }

    /**
     * A GROUP operand is judged on its expansion, and the silences this family keeps.
     *
     * The last row is the positive control: without it the silent rows would pass on a model that never
     * parsed.
     */
    @Test
    fun theExpansionAndTheSilences() {
        val actual = mutableMapOf<String, List<String>>()
        actual["a group whose expansion agrees"] = codes("MaxValue(Window) == [AuditOn]")
        actual["a group whose expansion disagrees"] = codes("MaxValue(Spread) == [AuditOn]")
        actual["a NUMBER after a date leader"] = codes("MaxValue(StartOn, Premium) == [AuditOn]")
        actual["a NUMBER as the LEADER, which another arm types"] =
            codes("MaxValue(Premium, StartOn) == [AuditOn]")
        actual["the operand-list Min, which carries the gate over OPERATIONS"] =
            codes("Min([StartOn], [PeriodFrom]) == [AuditOn]")
        actual["a complete date beside a year-month — the positive control"] =
            codes("MaxValue(StartOn, PeriodFrom) == [AuditOn]")

        val expected = mutableMapOf<String, List<String>>()
        expected["a group whose expansion agrees"] = emptyList()
        expected["a group whose expansion disagrees"] = listOf(incompatible)
        expected["a NUMBER after a date leader"] = listOf("MVK_DATE_AND_NONDATE")
        // The LEADER decides which arm types the list at all, so a non-temporal one is never this gate's.
        // A12 answers `MVK_NOT_SORTABLE` there and [fieldListOperationOperandFindings] now reports it, so
        // this row locks the HANDOVER between the two arms rather than a silence.
        expected["a NUMBER as the LEADER, which another arm types"] = listOf("MVK_NOT_SORTABLE")
        // The operand-list carriers take OPERATIONS, whose types this reader does not infer.
        expected["the operand-list Min, which carries the gate over OPERATIONS"] = emptyList()
        expected["a complete date beside a year-month — the positive control"] = listOf(incompatible)
        assertEquals(expected, actual, "the expansion and the silences")
    }
}
