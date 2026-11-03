package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.modelkit.DatePrecision
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.modelkit.ModelDocument
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.Rule
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Which calendar a stored, constant or computed date before 1893 is read on (KERNEL-FINDINGS KF665): kernel 31.1.1
 * reads every date with `java.time`, so the calendar is proleptic — the labels 1582-10-05 to 1582-10-14 are days and
 * 1500-02-29 is not. One exception survives from the hybrid calendar 30.8.1 used: a Julian leap day still parses
 * through A12's fallback parser, as its Julian instant, unless the model zone is Europe/Berlin, where A12 discards
 * the fallback before 1893. A real date before the 1583-10-16 floor is `datumFalsch`, and an unreal one is malformed
 * (`datumFormatFalsch`), so each stored row's code says which calendar judged it, and the Julian rows say which zone.
 *
 * The rival reading is 30.8.1's hybrid calendar, which gives the opposite code on the skipped labels and, under
 * Europe/Berlin, on the Julian leap days; 1582-10-04, 1582-10-15 and 1700-02-29 (unreal on both) are the rows both
 * calendars agree on. Each kind that carries a date is a carrier: a Date in two formats, a partially known Date, a
 * DateTime, and a range's start.
 *
 * Own domain (an insurance policy's inception, signing, renewal, signing instant and cover period).
 */
class StoredDateCalendarDiffTest {

    /** The draft's default zone, Europe/Berlin. */
    private val model: String = DocumentModelDraft.named("policy-historic-dates").locale("en_US")
        .rootGroup("Policy") { p -> p
            .dateField("Inception") { it.format("yyyy-MM-dd") }
            .dateField("Signed") { it.format("dd.MM.yyyy") }
            .dateField("Renewal") { it.format("yyyy-MM-dd").datePrecision(DatePrecision.DAY_OPTIONAL) }
            .dateTimeField("SignedAt") { it.format("yyyy-MM-dd'T'HH:mm:ss") }
            .dateRangeField("Cover") { it.format("yyyy-MM-dd").separator("/") } }
        .build().toJson()

    private val utcModel: String = ModelDocument.read(model).setTimeZone("UTC").toJson()

    private fun storedCodes(model: String, rows: List<String>) = rows.associateWith { iso ->
        listOf(
            "Inception" to """"Inception":"$iso"""",
            "Signed" to """"Signed":"${iso.substring(8, 10)}.${iso.substring(5, 7)}.${iso.substring(0, 4)}"""",
            "Renewal" to """"Renewal":"$iso"""",
            "SignedAt" to """"SignedAt":"${iso}T12:00:00"""",
            "Cover" to """"Cover":"$iso/1600-01-01"""",
        ).associate { (field, member) ->
            val json = """{"Policy":{$member}}"""
            DocumentDifferential.assertAgree(model, mapOf("$field $iso" to json))
            field to DocumentDifferential.kernelMessages(model, json, dynamic = true).map { it.code }
        }
    }

    private fun row(date: List<String>, range: List<String>) =
        mapOf("Inception" to date, "Signed" to date, "Renewal" to date, "SignedAt" to date, "Cover" to range)

    @Test
    fun aStoredDateIsRealOnTheProlepticCalendarOnEveryEngine() {
        assertEquals(
            mapOf(
                "1582-10-04" to row(FLOOR, RANGE_FLOOR),
                "1582-10-05" to row(FLOOR, RANGE_FLOOR),
                "1582-10-14" to row(FLOOR, RANGE_FLOOR),
                "1582-10-15" to row(FLOOR, RANGE_FLOOR),
                "1500-02-29" to row(MALFORMED, RANGE_MALFORMED),
                "1300-02-29" to row(MALFORMED, RANGE_MALFORMED),
                "1700-02-29" to row(MALFORMED, RANGE_MALFORMED),
                // Measured: a partially known date reports year 0 as datumFalsch where every other carrier finds it
                // malformed. That code is shared with the floor, so this row does not say which calendar judged it.
                "0000-03-01" to row(MALFORMED, RANGE_MALFORMED) + ("Renewal" to FLOOR),
            ),
            storedCodes(model, ROWS),
        )
    }

    /** The zone twin: under UTC A12 keeps its fallback parse, so a Julian leap day is a real date below the floor. */
    @Test
    fun aJulianLeapDayIsRealOnlyOutsideEuropeBerlin() {
        assertEquals(
            mapOf(
                "1500-02-29" to row(FLOOR, RANGE_FLOOR),
                "1300-02-29" to row(FLOOR, RANGE_FLOOR),
                "1700-02-29" to row(MALFORMED, RANGE_MALFORMED),
            ),
            storedCodes(utcModel, listOf("1500-02-29", "1300-02-29", "1700-02-29")),
        )
    }

    /**
     * A constant naming a Julian leap day passes admission, whose check parses on the hybrid calendar, and then has no
     * value under Europe/Berlin, which A12 raises as an evaluation fault; its neighbours compare as ordinary dates.
     * The condition is written out because the constant's spelling is the subject.
     */
    @Test
    fun aJulianLeapDayConstantIsAnEvaluationFaultUnderEuropeBerlin() {
        val ruled = listOf("29.02.1500" to "JULIAN_LEAP", "04.10.1582" to "LAST_JULIAN", "15.10.1582" to "FIRST_GREGORIAN")
            .fold(model) { json, (constant, code) ->
                RuleValidator().inject(json, DraftRule("/Policy", code, "../Inception",
                    "FieldFilled(Inception) And [Inception] > \"$constant\"", code, "ERROR"))
            }
        val document = """{"Policy":{"Inception":"2024-01-01"}}"""
        DocumentDifferential.assertAgree(ruled, mapOf("a modern inception" to document))
        val observed = DocumentDifferential.kernel(ruled, document, dynamic = true)
        assertEquals(listOf("FIRST_GREGORIAN", "JULIAN_LEAP", "LAST_JULIAN"), observed.messages.map { it.code })
        assertEquals(listOf("JULIAN_LEAP"), observed.authoredTexts.filter { FAULT in it }.map { it.substringBefore(' ') })
    }

    /** Under UTC the same constant is a date: the Julian 1500-02-29, which is the proleptic 1500-03-10. */
    @Test
    fun aJulianLeapDayConstantIsItsJulianInstantUnderUtc() {
        val ruled = listOf(
            "DifferenceInDays(\"29.02.1500\", Inception) == 191319" to "JULIAN_INSTANT",
            "DifferenceInDays(\"29.02.1500\", Inception) == 191328" to "MARCH_FIRST",
        ).fold(utcModel) { json, (condition, code) ->
            RuleValidator().inject(json, DraftRule("/Policy", code, "../Inception", "FieldFilled(Inception) And $condition", code, "ERROR"))
        }
        val document = """{"Policy":{"Inception":"2024-01-01"}}"""
        DocumentDifferential.assertAgree(ruled, mapOf("a modern inception" to document))
        assertEquals(listOf("JULIAN_INSTANT"), DocumentDifferential.kernelMessages(ruled, document, dynamic = true).map { it.code })
    }

    /**
     * A pre-cutover constant counts days on the proleptic calendar, so the ten labels 30.8.1 skipped are days: from
     * 04.10.1582 to 2024-01-01 is 161161 days, where the hybrid count is 161151. Each reading has a rule for a
     * difference and for a shift, so exactly the matching pair fires.
     */
    @Test
    fun aPreCutoverConstantCountsOnTheProlepticCalendar() {
        val ruled = listOf(
            "DifferenceInDays(\"04.10.1582\", Inception) == 161151" to "HYBRID_DIFFERENCE",
            "DifferenceInDays(\"04.10.1582\", Inception) == 161161" to "PROLEPTIC_DIFFERENCE",
            "[Inception] == AddDays(\"04.10.1582\", 161151)" to "HYBRID_SHIFT",
            "[Inception] == AddDays(\"04.10.1582\", 161161)" to "PROLEPTIC_SHIFT",
        ).fold(model) { json, (condition, code) ->
            RuleValidator().inject(json, DraftRule("/Policy", code, "../Inception", "FieldFilled(Inception) And $condition", code, "ERROR"))
        }
        val document = """{"Policy":{"Inception":"2024-01-01"}}"""
        DocumentDifferential.assertAgree(ruled, mapOf("a modern inception" to document))
        assertEquals(listOf("PROLEPTIC_DIFFERENCE", "PROLEPTIC_SHIFT"),
            DocumentDifferential.kernelMessages(ruled, document, dynamic = true).map { it.code }.sorted())
    }

    /**
     * `BaseYear` is the same kind of reading: under a base year of 1500 it is 1500-01-01, from which 2024-01-01 is
     * 191387 days where the hybrid count is 191378.
     */
    @Test
    fun aPreCutoverBaseYearCountsOnTheProlepticCalendar() {
        val based = DocumentModelDraft.named("policy-historic-base").locale("en_US").baseYear(1500)
            .rootGroup("Policy") { p -> p.dateField("Inception") { it.format("yyyy-MM-dd") } }
            .build().toJson()
        val ruled = listOf(
            "DifferenceInDays(BaseYear, Inception) == 191378" to "HYBRID_DIFFERENCE",
            "DifferenceInDays(BaseYear, Inception) == 191387" to "PROLEPTIC_DIFFERENCE",
        ).fold(based) { json, (condition, code) ->
            RuleValidator().inject(json, DraftRule("/Policy", code, "../Inception", "FieldFilled(Inception) And $condition", code, "ERROR"))
        }
        val document = """{"Policy":{"Inception":"2024-01-01"}}"""
        DocumentDifferential.assertAgree(ruled, mapOf("a modern inception" to document))
        assertEquals(listOf("PROLEPTIC_DIFFERENCE"),
            DocumentDifferential.kernelMessages(ruled, document, dynamic = true).map { it.code })
    }

    /**
     * Every other carrier of a pre-cutover date value is proleptic too, in a UTC model: a constructed `Date(29, 2, 1500)`
     * has no value (its extracted year is 0), a range written as months and completed in base year 1500 ends on
     * February 28, two year-less constants completed there are one day apart, and a date-time constant shifted across
     * the old cutover lands ten days before 15.10.1582. Each pair has a rule for either calendar, and exactly the
     * proleptic one fires.
     */
    @Test
    fun everyPreCutoverValueCountsOnTheProlepticCalendar() {
        val drafted = DocumentModelDraft.named("policy-historic-values").locale("en_US").baseYear(1500)
            .rootGroup("Policy") { p -> p
                .stringField("Reference") { }
                .dateRangeField("Season") { it.format("MM").separator("/") }
                .numberField("Day") { it.fractionDigits(0, 0).min("1").max("31") }
                .numberField("Month") { it.fractionDigits(0, 0).min("1").max("12") }
                .numberField("Year") { it.fractionDigits(0, 0).min("1").max("9999") } }
            .build().toJson()
        val utc = ModelDocument.read(drafted).setTimeZone("UTC").toJson()
        val model = BoundModel.ofJson(utc)
        val leapDay = Dsl.date(model.num(DAY).asNumber(), model.num(MONTH).asNumber(), model.num(YEAR).asNumber())
        val marchFirst = Dsl.dateValue("01.03.1500")
        val seasonEnd = Dsl.endOfDateRange(model.dateRange(SEASON))
        val yearlessSpan = Dsl.differenceInDays(Dsl.dateValue("28.02."), Dsl.dateValue("01.03."))
        val shifted = Dsl.differenceInDays(Dsl.addHours(Dsl.dateTimeValue("04.10.1582T23:00:00"), 1),
            Dsl.dateTimeValue("15.10.1582T12:00:00"))
        val rules = listOf(
            calendarRule(model, "HYBRID_CONSTRUCTED", leapDay.before(marchFirst)),
            calendarRule(model, "PROLEPTIC_CONSTRUCTED", Dsl.yearFromDate(leapDay).eq(0)),
            // The hybrid end is the Julian 1500-02-29, which is the instant the UTC constant `"29.02.1500"` reads as.
            calendarRule(model, "HYBRID_SEASON_END", seasonEnd.eq(Dsl.dateValue("29.02.1500"))),
            calendarRule(model, "PROLEPTIC_SEASON_END", seasonEnd.eq(Dsl.dateValue("28.02.1500"))),
            calendarRule(model, "HYBRID_YEARLESS_SPAN", yearlessSpan.eq(2)),
            calendarRule(model, "PROLEPTIC_YEARLESS_SPAN", yearlessSpan.eq(1)),
            calendarRule(model, "HYBRID_DATE_TIME_SHIFT", shifted.eq(0)),
            calendarRule(model, "PROLEPTIC_DATE_TIME_SHIFT", shifted.eq(10)),
        )
        val ruled = rules.fold(utc, TypedRules::inject)
        val document = """{"Policy":{"Reference":"P-1","Day":29,"Month":2,"Year":1500,"Season":"02/02"}}"""
        DocumentDifferential.assertAgree(ruled, mapOf("a Julian leap day" to document))
        assertEquals(
            listOf("PROLEPTIC_CONSTRUCTED", "PROLEPTIC_DATE_TIME_SHIFT", "PROLEPTIC_SEASON_END", "PROLEPTIC_YEARLESS_SPAN"),
            DocumentDifferential.kernelMessages(ruled, document, dynamic = true).map { it.code }.sorted(),
        )
    }

    private fun calendarRule(model: BoundModel, code: String, condition: Condition): Rule =
        model.rule(model.group("/Policy")).name(code).errorField(model.str(REFERENCE))
            .`when`(model.str(REFERENCE).filled().and(condition))
            .code(code).severity(Severity.ERROR).message(Dsl.en("Calendar probe.")).build()

    private companion object {
        const val REFERENCE = "/Policy/Reference"
        const val SEASON = "/Policy/Season"
        const val DAY = "/Policy/Day"
        const val MONTH = "/Policy/Month"
        const val YEAR = "/Policy/Year"
        val ROWS = listOf("1582-10-04", "1582-10-05", "1582-10-14", "1582-10-15", "1500-02-29", "1300-02-29", "1700-02-29", "0000-03-01")
        val FLOOR = listOf("datumFalsch")
        val MALFORMED = listOf("datumFormatFalsch")
        val RANGE_FLOOR = listOf("datumBereichDatumFalsch")
        val RANGE_MALFORMED = listOf("datumBereichFormatFalsch")
        const val FAULT = "(the evaluation-fault text)"
    }
}
