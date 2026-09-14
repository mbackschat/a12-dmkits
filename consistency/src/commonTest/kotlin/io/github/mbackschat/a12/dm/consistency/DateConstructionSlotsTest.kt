package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The checker's half of the `Date`/`Time` slot laws.
 *
 * Every row mirrors one measured against the real kernel by
 * `rulekit.validate.laws.DateConstructionSlotLawsTest`, on the same booking shape. The silent rows carry the
 * risk: an entity component, and the two `Date` arities this family refuses to judge.
 */
class DateConstructionSlotsTest {

    private val wrongFormat = "MVK_WRONG_DATE_FORMAT_FOR_OP"
    private val wrongSlot = "MVK_INVALID_DATE_OR_TIME_EXTRACT_OP"
    private val wrongFamily = "MVK_DATE_OR_TIME_EXTRACT_OP_NOT_ALLOWED"

    /**
     * A booking with a full date, a time, and a two-digit number component to fill a slot with.
     *
     * The construction's own quotes are escaped before they reach the JSON: an unescaped one ends the
     * `errorCondition` string, the source never parses, and every row expecting silence then passes on a
     * model that was never checked.
     */
    private fun codes(target: String, construction: String): List<String> {
        val quoted = construction.replace("\"", "\\\"")
        return check(
            ModelSource(
                """
            {"header":{"id":"booking","locales":[{"code":"en_US"}]},
             "content":{"modelRoot":{"rootGroups":[
              {"type":"Group","id":"g0","name":"Booking","Group":{"elements":[
                {"type":"Field","id":"f0","name":"BookingRef","Field":{"fieldType":{"type":"StringType"}}},
                {"type":"Field","id":"f1","name":"TravelDate","Field":{"fieldType":{"type":"DateType",
                  "DateType":{"format":"yyyy-MM-dd"}}}},
                {"type":"Field","id":"f2","name":"Departure","Field":{"fieldType":{"type":"TimeType",
                  "TimeType":{"format":"HH:mm:ss"}}}},
                {"type":"Field","id":"f3","name":"DayNo","Field":{"fieldType":{"type":"NumberType",
                  "NumberType":{"maxLength":2,"maxFractionalDigits":0}}}},
                {"type":"Rule","id":"r0","name":"Probe","Rule":{
                  "errorCondition":"FieldFilled($target) And [$target] == $quoted",
                  "errorEntityRelPath":"../$target","errorCode":"C1",
                  "errorMessage":[{"locale":"en_US","text":"x"}]}}
              ]}}
             ]}}}
                """.trimIndent(),
            ),
        ).findings.map { it.code }
    }

    private fun date(construction: String) = codes("TravelDate", construction)

    private fun time(construction: String) = codes("Departure", construction)

    @Test
    fun whatEachConstantSlotAdmits() {
        val actual = mutableMapOf<String, List<String>>()
        actual["a valid date — control"] = date("""Date("01", "02", "2024")""")
        actual["components written without a leading zero"] = date("""Date("1", "2", "2024")""")
        actual["day 32"] = date("""Date("32", "02", "2024")""")
        actual["month 13"] = date("""Date("01", "13", "2024")""")
        actual["year 1799"] = date("""Date("01", "02", "1799")""")
        actual["year written in two digits"] = date("""Date("01", "02", "24")""")
        actual["a non-numeric component"] = date("""Date("oh", "02", "2024")""")
        actual["a valid time — control"] = time("""Time("23", "59", "59")""")
        actual["hour 24"] = time("""Time("24", "00", "00")""")
        actual["second 60"] = time("""Time("23", "59", "60")""")

        val expected = mutableMapOf<String, List<String>>()
        expected["a valid date — control"] = emptyList()
        expected["components written without a leading zero"] = emptyList()
        expected["day 32"] = listOf(wrongFormat)
        expected["month 13"] = listOf(wrongFormat)
        expected["year 1799"] = listOf(wrongFormat)
        // The year is the only slot that also demands a WIDTH.
        expected["year written in two digits"] = listOf(wrongFormat)
        expected["a non-numeric component"] = listOf(wrongFormat)
        expected["a valid time — control"] = emptyList()
        expected["hour 24"] = listOf(wrongFormat)
        expected["second 60"] = listOf(wrongFormat)
        assertEquals(expected, actual, "each slot's constant bounds")
    }

    @Test
    fun whichExtractOperatorEachSlotExpects() {
        val actual = mutableMapOf<String, List<String>>()
        actual["the operator each date slot expects — control"] =
            date("Date(DayFromDate(TravelDate), MonthFromDate(TravelDate), YearFromDate(TravelDate))")
        actual["a date operator in the wrong date slot"] =
            date("Date(MonthFromDate(TravelDate), MonthFromDate(TravelDate), YearFromDate(TravelDate))")
        actual["QuarterFromDate, which no slot expects"] =
            date("Date(QuarterFromDate(TravelDate), MonthFromDate(TravelDate), YearFromDate(TravelDate))")
        actual["a TIME operator in a date slot"] =
            date("Date(HoursFromTime(Departure), MonthFromDate(TravelDate), YearFromDate(TravelDate))")
        actual["a time operator in the wrong time slot"] =
            time("Time(MinutesFromTime(Departure), MinutesFromTime(Departure), SecondsFromTime(Departure))")
        actual["a DATE operator in a time slot"] =
            time("Time(DayFromDate(TravelDate), MinutesFromTime(Departure), SecondsFromTime(Departure))")

        val expected = mutableMapOf<String, List<String>>()
        expected["the operator each date slot expects — control"] = emptyList()
        expected["a date operator in the wrong date slot"] = listOf(wrongSlot)
        // `QuarterFromDate` is a DATE operator no slot expects, so the two classes are not one rule.
        expected["QuarterFromDate, which no slot expects"] = listOf(wrongFamily)
        expected["a TIME operator in a date slot"] = listOf(wrongFamily)
        expected["a time operator in the wrong time slot"] = listOf(wrongSlot)
        expected["a DATE operator in a time slot"] = listOf(wrongFamily)
        assertEquals(expected, actual, "which extract operator each slot expects")
    }

    /** A12 answers at the earlier defective SLOT, whichever arm that slot carries. */
    @Test
    fun theEarlierSlotAnswersWhicheverArmItCarries() {
        val actual = mutableMapOf<String, List<String>>()
        actual["bad constant first, bad operator second"] =
            date("""Date("32", HoursFromTime(Departure), YearFromDate(TravelDate))""")
        actual["bad operator first, bad constant second"] =
            date("""Date(HoursFromTime(Departure), "13", "2024")""")

        val expected = mutableMapOf<String, List<String>>()
        expected["bad constant first, bad operator second"] = listOf(wrongFormat)
        expected["bad operator first, bad constant second"] = listOf(wrongFamily)
        assertEquals(expected, actual, "slots are answered left to right")
    }

    /**
     * The shapes it leaves alone, each because the class turns on something this family does not read.
     *
     * An ENTITY component is judged against its declared width and range, a different gate chain. A two-part
     * `Date` needs the model's base-year declaration, and a four-part one is the split-year H-model shape.
     * The last row is the positive control: without it three silent rows pass on a model that never parsed.
     */
    @Test
    fun theShapesItLeavesAlone() {
        val actual = mutableMapOf<String, List<String>>()
        actual["an entity component"] = date("""Date(DayNo, "02", "2024")""")
        actual["a two-part Date"] = date("""Date("01", "02")""")
        actual["a four-part Date"] = date("""Date("01", "02", "20", "24")""")
        actual["a bad constant in the same model — the positive control"] =
            date("""Date("32", "02", "2024")""")

        val expected = mutableMapOf<String, List<String>>()
        expected["an entity component"] = emptyList()
        expected["a two-part Date"] = emptyList()
        expected["a four-part Date"] = emptyList()
        expected["a bad constant in the same model — the positive control"] = listOf(wrongFormat)
        assertEquals(expected, actual, "the shapes it leaves alone")
    }
}
