package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The checker's half of the DATE-ARGUMENT slot every date operator hands its operands to.
 *
 * Every row mirrors one measured against the real kernel by `rulekit.validate.laws.DateArgumentSlotLawsTest`.
 * The rows that carry the file are the TEMPORAL ones: the slot's kind gate takes all four temporal
 * declarations, and what refuses a time or a fragment is the CARRIER's format gate one layer up — so a family
 * reading "a date slot takes a date" off the class name would name the wrong class on a correct rejection.
 *
 * Four of A12's answers have no owner in this checker and are DISCLOSED as empty rows rather than left to
 * read as agreement; each says which class A12 draws and which layer draws it.
 */
class DateArgumentSlotTest {

    private val notADate = listOf("MVK_NO_DATE")
    private val badString = listOf("MVK_INVALID_DATE_STRING")
    private val noGroups = listOf("MVK_NO_GROUPS_ALLOWED")
    private val noWildcards = listOf("MVK_NO_WILDCARDS_ALLOWED")

    /** An order model whose one rule carries [condition]; the field set is the laws test's, kind for kind. */
    private fun codes(condition: String): List<String> = checkRead(
        ModelSource(
            """
            {"header":{"modelVersion":"29.4.0","id":"order-date-arguments","locales":[{"code":"en_US"}]},
             "content":{"modelInfo":{"name":"model"},"modelRoot":{"rootGroups":[
              {"type":"Group","id":"g0","name":"Order","Group":{"elements":[
                {"type":"Field","id":"f0","name":"Reference","Field":{"fieldType":{"type":"StringType"}}},
                {"type":"Field","id":"f1","name":"Notes","Field":{"fieldType":{"type":"StringType"}}},
                {"type":"Field","id":"f2","name":"Quantity","Field":{"fieldType":{"type":"NumberType",
                  "NumberType":{"maxFractionalDigits":0}}}},
                {"type":"Field","id":"f3","name":"Expedited","Field":{"fieldType":{"type":"BooleanType"}}},
                {"type":"Field","id":"f4","name":"Acknowledged","Field":{"fieldType":{"type":"ConfirmType"}}},
                {"type":"Field","id":"f5","name":"Courier","Field":{"fieldType":{"type":"CustomFieldType",
                  "CustomFieldType":{"name":"CourierCode"}}}},
                {"type":"Field","id":"f6","name":"Channel","Field":{"fieldType":{"type":"EnumerationType",
                  "EnumerationType":{"values":[{"value":"WEB"},{"value":"STORE"}],
                  "categories":[{"name":"Band","values":["X","Y"]}]}}}},
                {"type":"Field","id":"f7","name":"OrderedOn","Field":{"fieldType":{"type":"DateType",
                  "DateType":{"format":"dd.MM.yyyy"}}}},
                {"type":"Field","id":"f8","name":"ShippedOn","Field":{"fieldType":{"type":"DateType",
                  "DateType":{"format":"dd.MM.yyyy"}}}},
                {"type":"Field","id":"f9","name":"PickupTime","Field":{"fieldType":{"type":"TimeType",
                  "TimeType":{"format":"HH:mm:ss"}}}},
                {"type":"Field","id":"f10","name":"ScheduledAt","Field":{"fieldType":{"type":"DateTimeType",
                  "DateTimeType":{"format":"yyyy-MM-dd'T'HH:mm:ss"}}}},
                {"type":"Field","id":"f11","name":"BillingMonth","Field":{"fieldType":{
                  "type":"DateFragmentType","DateFragmentType":{"formatOfFragment":"yyyy-MM"}}}},
                {"type":"Field","id":"f12","name":"DeliveryWindow","Field":{"fieldType":{
                  "type":"DateRangeType","DateRangeType":{"format":"dd.MM.yyyy","rangeSeparator":"-"}}}},
                {"type":"Group","id":"g1","name":"Billing","Group":{"elements":[
                  {"type":"Field","id":"f13","name":"InvoicedOn","Field":{"fieldType":{"type":"DateType",
                    "DateType":{"format":"dd.MM.yyyy"}}}}
                ]}},
                {"type":"Group","id":"g2","name":"Lines","Group":{"repeatability":20,"elements":[
                  {"type":"Field","id":"f14","name":"LineDate","Field":{"fieldType":{"type":"DateType",
                    "DateType":{"format":"dd.MM.yyyy"}}}}
                ]}},
                {"type":"Rule","id":"r0","name":"Probe","Rule":{
                  "errorCondition":"CONDITION_HERE","errorEntityRelPath":"../Reference",
                  "errorCode":"C1","errorMessage":[{"locale":"en_US","text":"x"}]}}
              ]}}
             ]}}}
            """.trimIndent().replace("CONDITION_HERE", condition),
        ),
    ).findings.map { it.code }

    /** The probe also READS the rule's error target, so the unreferenced-target family stays out of the row. */
    private fun slot(probe: String): List<String> = codes("FieldFilled(Reference) And $probe")
        .filter { it != "MVK_ERROR_FIELD_NOT_REFERENCED" }

    @Test
    fun whatTheFieldFormOfTheSlotDemands() {
        val actual = mutableMapOf<String, List<String>>()
        actual["two date fields — the control"] = slot("DifferenceInDays(OrderedOn, ShippedOn) > 0")
        actual["a group"] = slot("DifferenceInDays(Billing, ShippedOn) > 0")
        actual["a starred field"] = slot("DifferenceInDays(Lines*/LineDate, ShippedOn) > 0")
        actual["a star on the field, not on its level"] = slot("DifferenceInDays(Lines/LineDate*, ShippedOn) > 0")
        actual["a star on a fixed group above the field"] =
            slot("DifferenceInDays(Billing*/InvoicedOn, ShippedOn) > 0")
        actual["the same without the star — the twin"] = slot("DifferenceInDays(Billing/InvoicedOn, ShippedOn) > 0")
        actual["a category access"] = slot("DifferenceInDays(Channel -> Band, ShippedOn) > 0")
        actual["a text field"] = slot("DifferenceInDays(Notes, ShippedOn) > 0")
        actual["a number field"] = slot("DifferenceInDays(Quantity, ShippedOn) > 0")
        actual["a boolean field"] = slot("DifferenceInDays(Expedited, ShippedOn) > 0")
        actual["an enumeration field"] = slot("DifferenceInDays(Channel, ShippedOn) > 0")
        actual["a confirm field"] = slot("DifferenceInDays(Acknowledged, ShippedOn) > 0")
        actual["a CUSTOM-typed field"] = slot("DifferenceInDays(Courier, ShippedOn) > 0")
        actual["a TIME field"] = slot("DifferenceInDays(PickupTime, ShippedOn) > 0")
        actual["a DATE-TIME field"] = slot("DifferenceInDays(ScheduledAt, ShippedOn) > 0")
        actual["a DATE-FRAGMENT field"] = slot("DifferenceInDays(BillingMonth, ShippedOn) > 0")
        actual["a DATE-RANGE field"] = slot("DifferenceInDays(DeliveryWindow, ShippedOn) > 0")
        actual["the defect in the SECOND slot rather than the first"] =
            slot("DifferenceInDays(OrderedOn, Notes) > 0")

        val expected = mutableMapOf<String, List<String>>()
        expected["two date fields — the control"] = emptyList()
        expected["a group"] = noGroups
        expected["a starred field"] = noWildcards
        expected["a star on the field, not on its level"] = listOf("MVK_INVALID_WILDCARD")
        expected["a star on a fixed group above the field"] = listOf("MVK_INVALID_WILDCARD")
        expected["the same without the star — the twin"] = emptyList()
        // A12 answers `MVK_CATEGORY_NOT_ALLOWED`, from a gate ahead of every one here: the shared operand
        // reducer declines a categorized operand, so nothing in this checker ever sees the call.
        expected["a category access"] = emptyList()
        expected["a text field"] = notADate
        expected["a number field"] = notADate
        expected["a boolean field"] = notADate
        expected["an enumeration field"] = notADate
        expected["a confirm field"] = notADate
        expected["a CUSTOM-typed field"] = notADate
        // A12 answers `MVK_WRONG_DATE_FORMAT_FOR_OP` on the time and the fragment — the DIFFERENCE carrier's
        // own format gate, which no family here owns. The slot itself admits all four temporal kinds, so
        // reporting `MVK_NO_DATE` would be naming the wrong class on a correct rejection.
        expected["a TIME field"] = emptyList()
        expected["a DATE-TIME field"] = emptyList()
        expected["a DATE-FRAGMENT field"] = emptyList()
        expected["a DATE-RANGE field"] = notADate
        expected["the defect in the SECOND slot rather than the first"] = notADate
        assertEquals(expected, actual, "what the field form of the date-argument slot demands")
    }

    @Test
    fun whatTheStringFormOfTheSlotAccepts() {
        val actual = mutableMapOf<String, List<String>>()
        actual["a full day.month.year"] = slot("DifferenceInDays(\\\"01.02.2024\\\", ShippedOn) > 0")
        actual["a day.month. with no year, against a dated field"] =
            slot("DifferenceInDays(\\\"01.02.\\\", ShippedOn) > 0")
        actual["a day.month. with no year, against another of its kind"] =
            slot("DifferenceInDays(\\\"01.02.\\\", \\\"03.04.\\\") > 0")
        actual["a day.month with no trailing dot"] = slot("DifferenceInDays(\\\"01.02\\\", ShippedOn) > 0")
        actual["a TIME constant"] = slot("DifferenceInDays(\\\"10:30:00\\\", ShippedOn) > 0")
        actual["a DATE-TIME constant"] = slot("DifferenceInDays(\\\"01.02.2024T10:30:00\\\", ShippedOn) > 0")
        actual["a date-time constant with a SPACE instead of the T"] =
            slot("DifferenceInDays(\\\"01.02.2024 10:30:00\\\", ShippedOn) > 0")
        actual["the 29th of February with no year"] =
            slot("DifferenceInDays(\\\"29.02.\\\", \\\"03.04.\\\") > 0")
        actual["the 29th of February in a leap year"] =
            slot("DifferenceInDays(\\\"29.02.2024\\\", ShippedOn) > 0")
        actual["the 29th of February in a NON-leap year"] =
            slot("DifferenceInDays(\\\"29.02.2023\\\", ShippedOn) > 0")
        actual["an ISO spelling"] = slot("DifferenceInDays(\\\"2024-02-01\\\", ShippedOn) > 0")
        actual["a day out of range"] = slot("DifferenceInDays(\\\"32.01.2024\\\", ShippedOn) > 0")
        actual["a month out of range"] = slot("DifferenceInDays(\\\"01.13.2024\\\", ShippedOn) > 0")
        actual["no date at all"] = slot("DifferenceInDays(\\\"soon\\\", ShippedOn) > 0")
        actual["an empty string"] = slot("DifferenceInDays(\\\"\\\", ShippedOn) > 0")

        val expected = mutableMapOf<String, List<String>>()
        expected["a full day.month.year"] = emptyList()
        // A12 answers `MVK_DATE_WITH_AND_WITHOUT_YEAR` here — the carrier refusing the MIXTURE, one layer up
        // and unowned. The slot itself accepts the year-less spelling, which the next row shows alone.
        expected["a day.month. with no year, against a dated field"] = emptyList()
        expected["a day.month. with no year, against another of its kind"] = emptyList()
        // The LENGTH picks the pattern, so the year-less shape minus its trailing dot is not a shorter date.
        expected["a day.month with no trailing dot"] = badString
        // A12 answers `MVK_WRONG_DATE_FORMAT_FOR_OP` — again the carrier's, the slot taking a time constant.
        expected["a TIME constant"] = emptyList()
        expected["a DATE-TIME constant"] = emptyList()
        expected["a date-time constant with a SPACE instead of the T"] = badString
        // The year-less shape is NOT year-agnostic: the formatter's default year is the epoch's, 1970, and
        // the parse behind the pattern is non-lenient, so the calendar refuses a 29th of February there.
        expected["the 29th of February with no year"] = badString
        expected["the 29th of February in a leap year"] = emptyList()
        expected["the 29th of February in a NON-leap year"] = badString
        expected["an ISO spelling"] = badString
        expected["a day out of range"] = badString
        expected["a month out of range"] = badString
        expected["no date at all"] = badString
        expected["an empty string"] = badString
        assertEquals(expected, actual, "what the string form of the date-argument slot accepts")
    }

    @Test
    fun theSlotIsTheSameOnEveryCarrierThatTakesADate() {
        val actual = mutableMapOf<String, List<String>>()
        actual["a group under a SHIFT"] = slot("[ShippedOn] > AddDays(Billing, 7)")
        actual["a text under a SHIFT"] = slot("[ShippedOn] > AddDays(Notes, 7)")
        actual["a bad date string under a SHIFT"] = slot("[ShippedOn] > AddDays(\\\"soon\\\", 7)")
        actual["a text under an EXTRACTION"] = slot("YearFromDate(Notes) > 2020")
        actual["a group under an EXTRACTION"] = slot("YearFromDate(Billing) > 2020")
        actual["a starred field under an EXTRACTION"] = slot("YearFromDate(Lines*/LineDate) > 2020")
        actual["a text under another DIFFERENCE"] = slot("DifferenceInHours(Notes, ScheduledAt) > 0")
        actual["a text under another SHIFT"] = slot("[ShippedOn] > AddMonths(Notes, 3)")
        actual["a text under a CLOCK extraction"] = slot("HoursFromTime(Notes) > 9")
        actual["a text under a STAMP extraction"] = slot("DateFromDateTime(Notes) > Today")
        actual["a NUMERIC extraction nested in a date slot"] =
            slot("DifferenceInDays(YearFromDate(OrderedOn), ShippedOn) > 0")
        actual["a TEMPORAL extraction nested in a date slot"] =
            slot("DifferenceInDays(DateFromDateTime(ScheduledAt), ShippedOn) > 0")

        val expected = mutableMapOf<String, List<String>>()
        expected["a group under a SHIFT"] = noGroups
        expected["a text under a SHIFT"] = notADate
        expected["a bad date string under a SHIFT"] = badString
        expected["a text under an EXTRACTION"] = notADate
        expected["a group under an EXTRACTION"] = noGroups
        expected["a starred field under an EXTRACTION"] = noWildcards
        expected["a text under another DIFFERENCE"] = notADate
        expected["a text under another SHIFT"] = notADate
        expected["a text under a CLOCK extraction"] = notADate
        expected["a text under a STAMP extraction"] = notADate
        // The nested form's own gate: an extraction standing in a date slot must itself yield a date.
        expected["a NUMERIC extraction nested in a date slot"] = listOf("MVK_INVALID_DATE_FOR_OP")
        expected["a TEMPORAL extraction nested in a date slot"] = emptyList()
        assertEquals(expected, actual, "the slot is the same on every carrier that takes a date")
    }
}
