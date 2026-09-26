package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The checker's half of the `Date`/`Time` slot laws.
 *
 * The constant and operator rows mirror ones measured against the real kernel by
 * `rulekit.validate.laws.DateConstructionSlotLawsTest`, on the same booking shape; the entity rows mirror
 * `rulekit.validate.laws.TemporalComponentDeclarationLawsTest`, restricted to the three `Date` and three
 * `Time` slots this family judges. The silent rows carry the risk: a declared type left alone, the two
 * `Date` arities this family refuses to judge, and the component another family's class pre-empts.
 */
class DateConstructionSlotsTest {

    private val wrongFormat = "MVK_WRONG_DATE_FORMAT_FOR_OP"
    private val wrongSlot = "MVK_INVALID_DATE_OR_TIME_EXTRACT_OP"
    private val wrongFamily = "MVK_DATE_OR_TIME_EXTRACT_OP_NOT_ALLOWED"
    private val noDigitPattern = "MVK_NO_DIGIT_PATTERN"

    /**
     * A booking with a full date and a time, plus whatever component field the row declares.
     *
     * The construction's own quotes are escaped before they reach the JSON: an unescaped one ends the
     * `errorCondition` string, the source never parses, and every row expecting silence then passes on a
     * model that was never checked. [component] carries raw JSON and is deliberately not escaped.
     */
    private fun codes(target: String, construction: String, component: String = ""): List<String> {
        val quoted = construction.replace("\"", "\\\"")
        return checkRead(
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
                $component
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

    /**
     * The four-parameter SPLIT-YEAR form, which the ledger had as an H-model shape with no A12 reach.
     *
     * The rows that carry the correction are the two controls: the very same extract operator is ADMITTED in
     * the three-parameter year slot, so the refusal belongs to the split-year pair rather than to the
     * operator; and a well-formed split year draws nothing, so the family did not simply start refusing an
     * arity it used to ignore. The century's own constant range is deliberately not reported — A12 answers a
     * bad one through the comparison's format gate, which this family does not own.
     */
    @Test
    fun theSplitYearFormRefusesAnExtractOperatorInEitherYearSlot() {
        val actual = mutableMapOf<String, List<String>>()
        actual["a well-formed split year — the control"] = date("""Date("01", "02", "20", "24")""")
        actual["an extract op in the CENTURY slot"] =
            date("""Date("01", "02", YearFromDate(TravelDate), "24")""")
        actual["an extract op in the SHORT-YEAR slot"] =
            date("""Date("01", "02", "20", YearFromDate(TravelDate))""")
        actual["the same op in the three-parameter YEAR slot — the control"] =
            date("""Date("01", "02", YearFromDate(TravelDate))""")
        actual["a bad DAY beside a split year, so the earlier gate still answers first"] =
            date("""Date("32", "02", "20", "24")""")

        val expected = mutableMapOf<String, List<String>>()
        expected["a well-formed split year — the control"] = emptyList()
        expected["an extract op in the CENTURY slot"] = listOf(SPLIT_YEAR_EXTRACT)
        expected["an extract op in the SHORT-YEAR slot"] = listOf(SPLIT_YEAR_EXTRACT)
        expected["the same op in the three-parameter YEAR slot — the control"] = emptyList()
        // A12 walks day and month before it asks about the year at all, so the split-year law never gets to
        // answer over an earlier slot's defect — the ordering this family has to encode rather than infer.
        expected["a bad DAY beside a split year, so the earlier gate still answers first"] =
            listOf("MVK_WRONG_DATE_FORMAT_FOR_OP")
        assertEquals(expected, actual, "the four-parameter split-year date construction")
    }

    private val SPLIT_YEAR_EXTRACT = "MVK_EXTRACT_OP_NOT_ALLOWED_4_DATE_CONSTRUCT_WITH_4_PARAMS"

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
        actual["bad component first, bad constant second"] =
            atDay(text(""""maxLength":2""", "[0123456789]+"), month = "13")
        actual["good component first, bad constant second"] =
            atDay(number(""""minValue":0,"maxLength":2"""), month = "13")

        val expected = mutableMapOf<String, List<String>>()
        expected["bad constant first, bad operator second"] = listOf(wrongFormat)
        expected["bad operator first, bad constant second"] = listOf(wrongFamily)
        // The component's own class is the discriminator: a row where both slots draw the same code could
        // not tell the earlier slot answering from the later one.
        expected["bad component first, bad constant second"] = listOf(noDigitPattern)
        expected["good component first, bad constant second"] = listOf(wrongFormat)
        assertEquals(expected, actual, "slots are answered left to right")
    }

    /**
     * What a NUMBER component has to declare — the stored width or the slot's own bound, never neither.
     *
     * The three refusals that do not look like width failures are the ones worth the rows:
     * `maxIntegerDigits` is a different member from `maxLength` and does not satisfy the slot, any declared
     * scale disqualifies the field, and a component that says nothing about its sign is refused however right
     * its width is. The sign gate has two satisfiers, though: `positivesOnly` passes it with no minimum at
     * all, which is why the last pair of rows sits beside the negative-minimum one.
     */
    @Test
    fun whatANumberComponentHasToDeclare() {
        val actual = mutableMapOf<String, List<String>>()
        actual["the stored width of its slot — control"] = atDay(number(""""minValue":0,"maxLength":2"""))
        actual["the day's own maximum"] = atDay(number(""""minValue":0,"maxValue":31"""))
        actual["the month's own maximum"] = atMonth(number(""""minValue":0,"maxValue":12"""))
        actual["the complete year's stored width"] = atYear(number(""""minValue":0,"maxLength":4"""))
        actual["the complete year's own maximum"] = atYear(number(""""minValue":0,"maxValue":9999"""))
        actual["a year bounded at the flexible floor"] = atYear(number(""""minValue":0,"maxValue":1000"""))
        actual["a year bounded below that floor"] = atYear(number(""""minValue":0,"maxValue":999"""))
        actual["a width one too wide for the day"] = atDay(number(""""minValue":0,"maxLength":3"""))
        actual["integer digits instead of a stored width"] =
            atDay(number(""""minValue":0,"maxIntegerDigits":2"""))
        actual["a declared scale"] =
            atDay(number(""""minValue":0,"maxLength":2,"maxFractionalDigits":1"""))
        actual["no declared minimum at all"] = atDay(number(""""maxLength":2"""))
        actual["a negative minimum"] = atDay(number(""""minValue":-1,"maxLength":2"""))
        actual["the declared sign flag instead of a minimum"] =
            atDay(number(""""positivesOnly":true,"maxLength":2"""))
        actual["that same flag at a wrong width"] =
            atDay(number(""""positivesOnly":true,"maxLength":3"""))
        actual["the hour's own maximum"] = atHour(number(""""minValue":0,"maxValue":23"""))
        actual["the minute's maximum in the hour slot"] = atHour(number(""""minValue":0,"maxValue":59"""))
        actual["the hour's maximum in the minute slot"] = atMinute(number(""""minValue":0,"maxValue":23"""))

        val expected = mutableMapOf<String, List<String>>()
        expected["the stored width of its slot — control"] = emptyList()
        expected["the day's own maximum"] = emptyList()
        expected["the month's own maximum"] = emptyList()
        expected["the complete year's stored width"] = emptyList()
        // 9999 is the year's field bound, where 2199 is its CONSTANT bound — two different numbers.
        expected["the complete year's own maximum"] = emptyList()
        expected["a year bounded at the flexible floor"] = emptyList()
        expected["a year bounded below that floor"] = listOf(wrongFormat)
        expected["a width one too wide for the day"] = listOf(wrongFormat)
        expected["integer digits instead of a stored width"] = listOf(wrongFormat)
        expected["a declared scale"] = listOf(wrongFormat)
        expected["no declared minimum at all"] = listOf(wrongFormat)
        expected["a negative minimum"] = listOf(wrongFormat)
        expected["the declared sign flag instead of a minimum"] = emptyList()
        expected["that same flag at a wrong width"] = listOf(wrongFormat)
        expected["the hour's own maximum"] = emptyList()
        expected["the minute's maximum in the hour slot"] = listOf(wrongFormat)
        expected["the hour's maximum in the minute slot"] = listOf(wrongFormat)
        assertEquals(expected, actual, "what a number component has to declare")
    }

    /**
     * The string arm's two gates, in the order A12 runs them.
     *
     * The pattern gate answers first, so a component of exactly the right width still draws the digit-pattern
     * class when its pattern is one A12 does not recognise — and `[0123456789]+` describes the same language
     * as `[0-9]+`, which is why the recognised set is a list of spellings rather than a regex equivalence.
     */
    @Test
    fun theTwoGatesAStringComponentPasses() {
        val actual = mutableMapOf<String, List<String>>()
        for (pattern in listOf("[0-9]+", "[0-9]*", """\d+""", """\d*""", "[0-9]{4}", """\d{4}""")) {
            actual["a complete year patterned $pattern"] = atYear(text(""""maxLength":4""", pattern))
        }
        actual["a two-character day patterned [0-9]+"] = atDay(text(""""maxLength":2""", "[0-9]+"))
        actual["the same day patterned [0123456789]+"] =
            atDay(text(""""maxLength":2""", "[0123456789]+"))
        actual["the same day with no pattern at all"] = atDay(text(""""maxLength":2"""))
        actual["a digit pattern one character too wide"] = atDay(text(""""maxLength":3""", "[0-9]+"))

        val expected = mutableMapOf<String, List<String>>()
        for (pattern in listOf("[0-9]+", "[0-9]*", """\d+""", """\d*""", "[0-9]{4}", """\d{4}""")) {
            expected["a complete year patterned $pattern"] = emptyList()
        }
        expected["a two-character day patterned [0-9]+"] = emptyList()
        expected["the same day patterned [0123456789]+"] = listOf(noDigitPattern)
        expected["the same day with no pattern at all"] = listOf(noDigitPattern)
        expected["a digit pattern one character too wide"] = listOf(wrongFormat)
        assertEquals(expected, actual, "the two gates a string component passes")
    }

    /** The one temporal component a slot admits: a whole year, declared exactly `yyyy`, and nowhere else. */
    @Test
    fun theOneTemporalComponentASlotAdmits() {
        val actual = mutableMapOf<String, List<String>>()
        actual["a yyyy date at the complete year"] = atYear(dateField("yyyy"))
        actual["the same field at the day"] = atDay(dateField("yyyy"))
        actual["the same field at the month"] = atMonth(dateField("yyyy"))
        actual["a full date at the complete year"] = atYear(dateField("yyyy-MM-dd"))

        val expected = mutableMapOf<String, List<String>>()
        expected["a yyyy date at the complete year"] = emptyList()
        expected["the same field at the day"] = listOf(wrongFormat)
        expected["the same field at the month"] = listOf(wrongFormat)
        expected["a full date at the complete year"] = listOf(wrongFormat)
        assertEquals(expected, actual, "the one temporal component a slot admits")
    }

    /**
     * The shapes it leaves alone, each because the class turns on something this family does not read.
     *
     * A `TimeType` component stands for every declared type outside the judged three: A12 refuses it and this
     * family stays silent, because a `TypeDefType` is refused the same way and its body may not be visible
     * here. A two-part `Date` needs the model's base-year declaration, and a four-part one is the split-year
     * H-model shape. The last row is the positive control: without it the silent rows would pass on a model
     * that never parsed.
     */
    @Test
    fun theShapesItLeavesAlone() {
        val actual = mutableMapOf<String, List<String>>()
        actual["a component of an unjudged declared type"] = atDay(timeField())
        actual["a two-part Date"] = date("""Date("01", "02")""")
        actual["a four-part Date"] = date("""Date("01", "02", "20", "24")""")
        actual["a bad constant in the same model — the positive control"] =
            date("""Date("32", "02", "2024")""")

        val expected = mutableMapOf<String, List<String>>()
        expected["a component of an unjudged declared type"] = emptyList()
        // This family still leaves the two-part shape's YEAR alone — the code is `BASE_YEAR_REQUIREMENT`'s,
        // measured 2026-09-14 by `rulekit.validate.laws.BaseYearRequirementLawsTest` after this row had
        // assumed A12 was silent here. Its two written slots ARE judged by this family, which is what the
        // day-out-of-range row of that same grid shows.
        expected["a two-part Date"] = listOf("MVK_MISSING_YEAR")
        expected["a four-part Date"] = emptyList()
        expected["a bad constant in the same model — the positive control"] = listOf(wrongFormat)
        assertEquals(expected, actual, "the shapes it leaves alone")
    }

    /**
     * Which family answers when one component is both a cross-repetition read and a wrong declaration.
     *
     * A12 draws the reference class alone and never reaches the slot gate, so this family has to be silent
     * there or the report carries two findings where A12 carries one. The third row is the control that keeps
     * the silence narrow: read from inside the repeating group, the same declaration draws the slot class.
     */
    @Test
    fun whichFamilyAnswersOnACrossRepetitionComponent() {
        val actual = mutableMapOf<String, List<String>>()
        actual["a wrongly declared component across an unstarred repetition"] =
            legs(Locus.ROOT, """Date(Legs/LegDay, "02", "2024")""")
        actual["a sound constant across the same repetition — control"] =
            legs(Locus.ROOT, """Date("01", "02", "2024") And FieldFilled(Legs/LegDay)""")
        actual["the same wrong declaration with nothing crossed — control"] =
            legs(Locus.LEG, """Date(LegDay, "02", "2024")""")

        val expected = mutableMapOf<String, List<String>>()
        expected["a wrongly declared component across an unstarred repetition"] = listOf("MVK_NO_WILDCARD")
        expected["a sound constant across the same repetition — control"] = listOf("MVK_NO_WILDCARD")
        expected["the same wrong declaration with nothing crossed — control"] = listOf(wrongFormat)
        assertEquals(expected, actual, "which family answers on a cross-repetition component")
    }

    /**
     * The booking with a repeatable leg list whose number field is one character too wide for a day.
     *
     * The probe rule is placed by its [Locus], which is the one variable these rows move: from the root it
     * crosses the leg repetition to reach `LegDay`, and from inside the list it crosses nothing.
     */
    private fun legs(locus: Locus, construction: String): List<String> {
        val quoted = construction.replace("\"", "\\\"")
        val probe = """
            ,{"type":"Rule","id":"r0","name":"Probe","Rule":{
              "errorCondition":"FieldFilled(${locus.target}) And [${locus.target}] == $quoted",
              "errorEntityRelPath":"../${locus.target}","errorCode":"C1",
              "errorMessage":[{"locale":"en_US","text":"x"}]}}
        """.trimIndent()
        return checkRead(
            ModelSource(
                """
            {"header":{"id":"booking-legs","locales":[{"code":"en_US"}]},
             "content":{"modelRoot":{"rootGroups":[
              {"type":"Group","id":"g0","name":"Booking","Group":{"elements":[
                {"type":"Field","id":"f0","name":"TravelDate","Field":{"fieldType":{"type":"DateType",
                  "DateType":{"format":"yyyy-MM-dd"}}}},
                {"type":"Group","id":"g1","name":"Legs","Group":{"repeatability":9,"elements":[
                  {"type":"Field","id":"f1","name":"LegDate","Field":{"fieldType":{"type":"DateType",
                    "DateType":{"format":"yyyy-MM-dd"}}}},
                  {"type":"Field","id":"f2","name":"LegDay","Field":{"fieldType":{"type":"NumberType",
                    "NumberType":{"minValue":0,"maxLength":3}}}}
                  ${if (locus == Locus.LEG) probe else ""}
                ]}}
                ${if (locus == Locus.ROOT) probe else ""}
              ]}}
             ]}}}
                """.trimIndent(),
            ),
        ).findings.map { it.code }
    }

    /** Where the probe rule sits — the one variable the cross-repetition rows move. */
    private enum class Locus(val target: String) {
        ROOT("TravelDate"),
        LEG("LegDate"),
    }

    private fun atDay(component: String, month: String = "02") =
        codes("TravelDate", """Date(Part, "$month", "2024")""", component)

    private fun atMonth(component: String) = codes("TravelDate", """Date("01", Part, "2024")""", component)

    private fun atYear(component: String) = codes("TravelDate", """Date("01", "02", Part)""", component)

    private fun atHour(component: String) = codes("Departure", """Time(Part, "00", "00")""", component)

    private fun atMinute(component: String) = codes("Departure", """Time("00", Part, "00")""", component)

    /** The component field every entity row declares, named `Part` so the row reads as its declaration. */
    private fun part(fieldType: String) =
        """{"type":"Field","id":"f3","name":"Part","Field":{"fieldType":$fieldType}},"""

    private fun braces(members: String) = "{$members}"

    private fun number(members: String) = part(braces(""""type":"NumberType","NumberType":{$members}"""))

    /** A string component; A12 demands a message beside a `pattern`, so the helper supplies one. */
    private fun text(members: String, pattern: String? = null): String {
        val patterned = pattern?.let {
            val escaped = it.replace("\\", "\\\\")
            ""","pattern":"$escaped","errorMessage":[{"locale":"en_US","text":"digits only"}]"""
        } ?: ""
        return part(braces(""""type":"StringType","StringType":{$members$patterned}"""))
    }

    private fun dateField(format: String) =
        part(braces(""""type":"DateType","DateType":{"format":"$format"}"""))

    /** A component of a type outside the judged three — refused by A12, silent here. */
    private fun timeField() = part(braces(""""type":"TimeType","TimeType":{"format":"HH:mm:ss"}"""))
}
