package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The checker's half of what a range-overlap operator demands of the ranges it compares.
 *
 * Every row mirrors one measured against the real kernel by
 * `rulekit.validate.laws.DateRangeOperandListLawsTest`. The rows that carry the file are the PRE-EMPTED ones
 * on the value-list carrier: a wrong kind there is a base-type disagreement rather than "not a range", and
 * the row with two agreeing non-ranges is what shows the range walk is still reachable.
 */
class DateRangeOperandListTest {

    private val notARange = listOf("MVK_NO_DATE_RANGE")
    private val badRangeFormat = listOf("MVK_INVALID_DATE_RANGE_FORMAT")
    private val mixedYear = listOf("MVK_DATE_WITH_AND_WITHOUT_YEAR")
    private val varyingTypes = listOf("MVK_VARYING_TYPES_NOT_ALLOWED")

    private fun codes(condition: String, baseYear: Boolean): List<String> = checkRead(
        ModelSource(
            """
            {"header":{"modelVersion":"29.4.0","id":"policy-coverage-windows","locales":[{"code":"en_US"}]},
             "content":{"modelInfo":{BASE_YEAR"name":"policy"},"modelRoot":{"rootGroups":[
              {"type":"Group","id":"g0","name":"Policy","Group":{"elements":[
                {"type":"Field","id":"f0","name":"Reference","Field":{"fieldType":{"type":"StringType"}}},
                {"type":"Field","id":"f1","name":"Holder","Field":{"fieldType":{"type":"StringType"}}},
                {"type":"Field","id":"f2","name":"Premium","Field":{"fieldType":{"type":"NumberType",
                  "NumberType":{"maxFractionalDigits":2}}}},
                {"type":"Field","id":"f3","name":"IssuedOn","Field":{"fieldType":{"type":"DateType",
                  "DateType":{"format":"dd.MM.yyyy"}}}},
                {"type":"Field","id":"f4","name":"Season","Field":{"fieldType":{"type":"DateRangeType",
                  "DateRangeType":{"format":"dd.MM.yyyy","rangeSeparator":"-"}}}},
                {"type":"Field","id":"f5","name":"Window","Field":{"fieldType":{"type":"DateRangeType",
                  "DateRangeType":{"format":"dd.MM.yyyy","rangeSeparator":"-"}}}},
                {"type":"Field","id":"f6","name":"Yearless","Field":{"fieldType":{"type":"DateRangeType",
                  "DateRangeType":{"format":"dd.MM","rangeSeparator":"-"}}}},
                {"type":"Field","id":"f7","name":"AlsoYearless","Field":{"fieldType":{"type":"DateRangeType",
                  "DateRangeType":{"format":"dd.MM","rangeSeparator":"-"}}}},
                {"type":"Field","id":"f8","name":"RollsForward","Field":{"fieldType":{"type":"DateRangeType",
                  "DateRangeType":{"format":"dd.MM","rangeSeparator":"-","interpretationOfYear":"FROM"}}}},
                {"type":"Field","id":"f9","name":"RollsBack","Field":{"fieldType":{"type":"DateRangeType",
                  "DateRangeType":{"format":"dd.MM","rangeSeparator":"-","interpretationOfYear":"TO"}}}},
                {"type":"Group","id":"g1","name":"Broker","Group":{"elements":[
                  {"type":"Field","id":"f10","name":"BrokerName","Field":{"fieldType":{"type":"StringType"}}}
                ]}},
                {"type":"Group","id":"g2","name":"Claims","Group":{"repeatability":20,"elements":[
                  {"type":"Field","id":"f11","name":"ClaimWindow","Field":{"fieldType":{
                    "type":"DateRangeType","DateRangeType":{"format":"dd.MM.yyyy","rangeSeparator":"-"}}}}
                ]}},
                {"type":"Group","id":"g3","name":"Terms","Group":{"elements":[
                  {"type":"Field","id":"f12","name":"TermOne","Field":{"fieldType":{"type":"DateRangeType",
                    "DateRangeType":{"format":"dd.MM.yyyy","rangeSeparator":"-"}}}},
                  {"type":"Field","id":"f13","name":"TermTwo","Field":{"fieldType":{"type":"DateRangeType",
                    "DateRangeType":{"format":"dd.MM.yyyy","rangeSeparator":"-"}}}}
                ]}},
                {"type":"Group","id":"g4","name":"Renewals","Group":{"elements":[
                  {"type":"Field","id":"f14","name":"RenewalOne","Field":{"fieldType":{
                    "type":"DateRangeType","DateRangeType":{"format":"dd.MM.yyyy","rangeSeparator":"-"}}}},
                  {"type":"Field","id":"f15","name":"RenewalTwo","Field":{"fieldType":{
                    "type":"DateRangeType","DateRangeType":{"format":"yyyy-MM-dd","rangeSeparator":"/"}}}}
                ]}},
                {"type":"Rule","id":"r0","name":"Probe","Rule":{
                  "errorCondition":"CONDITION_HERE","errorEntityRelPath":"../Reference",
                  "errorCode":"C1","errorMessage":[{"locale":"en_US","text":"x"}]}}
              ]}}
             ]}}}
            """.trimIndent()
                .replace("BASE_YEAR", if (baseYear) "\"baseYear\":\"2024\"," else "")
                .replace("CONDITION_HERE", condition),
        ),
    ).findings.map { it.code }

    /** The probe also READS the rule's error target, so the unreferenced-target family stays out of the row. */
    private fun ranges(probe: String): List<String> =
        codes("FieldFilled(Reference) And $probe", baseYear = false)
            .filter { it != "MVK_ERROR_FIELD_NOT_REFERENCED" }

    private fun withBaseYear(probe: String): List<String> =
        codes("FieldFilled(Reference) And $probe", baseYear = true)
            .filter { it != "MVK_ERROR_FIELD_NOT_REFERENCED" }

    @Test
    fun whatTheRangeListDemandsOfEachMember() {
        val actual = mutableMapOf<String, List<String>>()
        actual["two standard ranges — the control"] = ranges("DateRangesOverlap(Season, Window)")
        actual["a text field"] = ranges("DateRangesOverlap(Holder, Window)")
        actual["a number field"] = ranges("DateRangesOverlap(Premium, Window)")
        actual["a plain DATE field"] = ranges("DateRangesOverlap(IssuedOn, Window)")
        actual["the non-range in the SECOND position"] = ranges("DateRangesOverlap(Season, Holder)")
        actual["a group"] = ranges("DateRangesOverlap(Broker, Window)")
        actual["a FROM-interpreting range"] = ranges("DateRangesOverlap(RollsForward, Window)")
        actual["a TO-interpreting range"] = ranges("DateRangesOverlap(RollsBack, Window)")
        actual["a year-interpreting range in the SECOND position"] =
            ranges("DateRangesOverlap(Season, RollsBack)")
        actual["a starred range, which this arm never asks about"] =
            ranges("DateRangesOverlap(Claims*/ClaimWindow, Window)")
        actual["one operand only"] = ranges("DateRangesOverlap(Season)")
        actual["one operand, and a GROUP"] = ranges("DateRangesOverlap(Broker)")

        val expected = mutableMapOf<String, List<String>>()
        expected["two standard ranges — the control"] = emptyList()
        expected["a text field"] = notARange
        expected["a number field"] = notARange
        expected["a plain DATE field"] = notARange
        expected["the non-range in the SECOND position"] = notARange
        expected["a group"] = listOf("MVK_NO_GROUPS_ALLOWED")
        expected["a FROM-interpreting range"] = badRangeFormat
        expected["a TO-interpreting range"] = badRangeFormat
        expected["a year-interpreting range in the SECOND position"] = badRangeFormat
        expected["a starred range, which this arm never asks about"] = emptyList()
        expected["one operand only"] = listOf("MVK_PARAMSIZE_INVALIDFN")
        // A lone group fails both of A12's pre-gates and the group one answers, which is why this family
        // asks it ahead of declining on arity. The arity family stays out of the row on its own account —
        // its law is about how many FIELDS a list names, so a group operand is not its question.
        expected["one operand, and a GROUP"] = listOf("MVK_NO_GROUPS_ALLOWED")
        assertEquals(expected, actual, "what the range list demands of each member")
    }

    @Test
    fun whenTheListMustAgreeAboutCarryingAYear() {
        val actual = mutableMapOf<String, List<String>>()
        actual["two year-less ranges"] = ranges("DateRangesOverlap(Yearless, AlsoYearless)")
        actual["a dated range and a year-less one"] = ranges("DateRangesOverlap(Season, Yearless)")
        actual["the year-less one first"] = ranges("DateRangesOverlap(Yearless, Season)")
        actual["three ranges, the disagreement in the last"] =
            ranges("DateRangesOverlap(Season, Window, Yearless)")
        actual["a year mismatch AND a non-range together"] = ranges("DateRangesOverlap(Yearless, Holder)")
        actual["a year mismatch AND a year-interpreting range together"] =
            ranges("DateRangesOverlap(Season, RollsForward)")
        actual["the same mismatch under a declared BASE YEAR"] =
            withBaseYear("DateRangesOverlap(Season, Yearless)")
        actual["a year-interpreting range under a declared BASE YEAR"] =
            withBaseYear("DateRangesOverlap(RollsForward, Window)")
        actual["the control under a declared BASE YEAR"] = withBaseYear("DateRangesOverlap(Season, Window)")

        val expected = mutableMapOf<String, List<String>>()
        expected["two year-less ranges"] = emptyList()
        expected["a dated range and a year-less one"] = mixedYear
        expected["the year-less one first"] = mixedYear
        expected["three ranges, the disagreement in the last"] = mixedYear
        // The per-member sweep completes before the agreement walk begins, so these two answer with the
        // earlier class even though the list also disagrees about the year.
        expected["a year mismatch AND a non-range together"] = notARange
        expected["a year mismatch AND a year-interpreting range together"] = badRangeFormat
        expected["the same mismatch under a declared BASE YEAR"] = emptyList()
        expected["a year-interpreting range under a declared BASE YEAR"] = badRangeFormat
        expected["the control under a declared BASE YEAR"] = emptyList()
        assertEquals(expected, actual, "when the list must agree about carrying a year")
    }

    @Test
    fun theValueListCarrierAsksTheSameTwoQuestions() {
        val actual = mutableMapOf<String, List<String>>()
        actual["a range against a range — the control"] =
            ranges("AtLeastOneDateRangeOverlaps(Season In Window)")
        actual["a text subject"] = ranges("AtLeastOneDateRangeOverlaps(Holder In Window)")
        actual["a text value"] = ranges("AtLeastOneDateRangeOverlaps(Season In Holder)")
        actual["two texts, which AGREE and so reach the range walk"] =
            ranges("AtLeastOneDateRangeOverlaps(Holder In Reference)")
        actual["a DATE value"] = ranges("AtLeastOneDateRangeOverlaps(Season In IssuedOn)")
        actual["a year-interpreting SUBJECT"] = ranges("AtLeastOneDateRangeOverlaps(RollsForward In Window)")
        actual["a year-interpreting VALUE"] = ranges("AtLeastOneDateRangeOverlaps(Season In RollsBack)")
        actual["a year-less value against a dated subject"] =
            ranges("AtLeastOneDateRangeOverlaps(Season In Yearless)")
        actual["two year-less ranges"] = ranges("AtLeastOneDateRangeOverlaps(Yearless In AlsoYearless)")
        actual["a GROUP of two ranges sharing one format"] =
            ranges("AtLeastOneDateRangeOverlaps(Season In Terms)")
        actual["a GROUP of two DATED ranges spelled differently"] =
            ranges("AtLeastOneDateRangeOverlaps(Season In Renewals)")
        actual["the mismatch under a declared BASE YEAR"] =
            withBaseYear("AtLeastOneDateRangeOverlaps(Season In Yearless)")

        val expected = mutableMapOf<String, List<String>>()
        expected["a range against a range — the control"] = emptyList()
        // The base-type pre-gate belongs to `VALUE_LIST_CONDITION_GATES` and answers first on both sides,
        // which is why this family declines rather than naming its own kind class here.
        expected["a text subject"] = varyingTypes
        expected["a text value"] = varyingTypes
        expected["two texts, which AGREE and so reach the range walk"] = notARange
        expected["a DATE value"] = varyingTypes
        expected["a year-interpreting SUBJECT"] = badRangeFormat
        expected["a year-interpreting VALUE"] = badRangeFormat
        expected["a year-less value against a dated subject"] = mixedYear
        expected["two year-less ranges"] = emptyList()
        expected["a GROUP of two ranges sharing one format"] = emptyList()
        // A12's distinct-FORMATS step, reproduced rather than simplified: both formats carry a year and the
        // reference is still refused, so a reader asking only "does this carry a year" would admit it.
        expected["a GROUP of two DATED ranges spelled differently"] = mixedYear
        expected["the mismatch under a declared BASE YEAR"] = emptyList()
        assertEquals(expected, actual, "the value-list carrier asks the same two questions")
    }
}
