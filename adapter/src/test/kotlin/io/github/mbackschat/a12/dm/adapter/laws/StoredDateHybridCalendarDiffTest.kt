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
 * Whether a stored date before the Gregorian cutover is real, which A12 reads on the Julian–Gregorian hybrid
 * calendar (KERNEL-FINDINGS KF588): the ten labels 1582-10-05 to 1582-10-14 do not exist, and a Julian leap day such
 * as 1500-02-29 does. A real date there is below the 1583-10-16 floor (`datumFalsch`), and an unreal one is
 * malformed (`datumFormatFalsch`), so each row's code says which calendar judged it.
 *
 * The rival reading is the proleptic Gregorian calendar, which gives the opposite code on both the skipped labels
 * and the Julian leap days; 1582-10-04, 1582-10-15 and 1700-02-29 (unreal in both, since 1700 is Gregorian) are the
 * rows both calendars agree on. Each kind that carries a date is a carrier: a Date in two formats, a partially known Date, a DateTime, and a range's start.
 *
 * Own domain (an insurance policy's inception, signing, renewal, signing instant and cover period).
 */
class StoredDateHybridCalendarDiffTest {

    private val model: String = DocumentModelDraft.named("policy-historic-dates").locale("en_US")
        .rootGroup("Policy") { p -> p
            .dateField("Inception") { it.format("yyyy-MM-dd") }
            .dateField("Signed") { it.format("dd.MM.yyyy") }
            .dateField("Renewal") { it.format("yyyy-MM-dd").datePrecision(DatePrecision.DAY_OPTIONAL) }
            .dateTimeField("SignedAt") { it.format("yyyy-MM-dd'T'HH:mm:ss") }
            .dateRangeField("Cover") { it.format("yyyy-MM-dd").separator("/") } }
        .build().toJson()

    @Test
    fun aStoredDateIsRealOnTheHybridCalendarOnEveryEngine() {
        val observed = ROWS.associateWith { iso ->
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
        val floor = listOf("datumFalsch")
        val malformed = listOf("datumFormatFalsch")
        fun row(date: List<String>, range: List<String>) =
            mapOf("Inception" to date, "Signed" to date, "Renewal" to date, "SignedAt" to date, "Cover" to range)
        assertEquals(
            mapOf(
                "1582-10-04" to row(floor, RANGE_FLOOR),
                "1582-10-05" to row(malformed, RANGE_MALFORMED),
                "1582-10-14" to row(malformed, RANGE_MALFORMED),
                "1582-10-15" to row(floor, RANGE_FLOOR),
                "1500-02-29" to row(floor, RANGE_FLOOR),
                "1300-02-29" to row(floor, RANGE_FLOOR),
                "1700-02-29" to row(malformed, RANGE_MALFORMED),
                // Measured: a partially known date reports year 0 as datumFalsch where every other carrier finds it
                // malformed. That code is shared with the floor, so this row does not say which calendar judged it.
                "0000-03-01" to row(malformed, RANGE_MALFORMED) + ("Renewal" to floor),
            ),
            observed,
        )
    }

    /**
     * A constant naming a Julian leap day is a date to A12, so a modern stored date compares above it on every
     * engine; the condition is written out because the constant's spelling is the subject.
     */
    @Test
    fun aJulianLeapDayConstantComparesAsADate() {
        val ruled = listOf("29.02.1500" to "JULIAN_LEAP", "04.10.1582" to "LAST_JULIAN", "15.10.1582" to "FIRST_GREGORIAN")
            .fold(model) { json, (constant, code) ->
                RuleValidator().inject(json, DraftRule("/Policy", code, "../Inception",
                    "FieldFilled(Inception) And [Inception] > \"$constant\"", code, "ERROR"))
            }
        DocumentDifferential.assertAgree(ruled, mapOf("a modern inception" to """{"Policy":{"Inception":"2024-01-01"}}"""))
        assertEquals(listOf("FIRST_GREGORIAN", "JULIAN_LEAP", "LAST_JULIAN"),
            DocumentDifferential.kernelMessages(ruled, """{"Policy":{"Inception":"2024-01-01"}}""", dynamic = true).map { it.code })
    }

    /**
     * A pre-cutover constant counts days on the hybrid calendar as well, so the ten skipped labels are no days: from
     * the Julian 04.10.1582 to 2024-01-01 is 161151 days, where the proleptic count is 161161. Each reading has a
     * rule for a difference and for a shift, so exactly the matching pair fires.
     */
    @Test
    fun aPreCutoverConstantCountsOnTheHybridCalendar() {
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
        assertEquals(listOf("HYBRID_DIFFERENCE", "HYBRID_SHIFT"),
            DocumentDifferential.kernelMessages(ruled, document, dynamic = true).map { it.code }.sorted())
    }

    /**
     * `BaseYear` is the same kind of reading: under a base year of 1500 it is the Julian 1500-01-01, from which
     * 2024-01-01 is 191378 days where the proleptic count is 191387.
     */
    @Test
    fun aPreCutoverBaseYearCountsOnTheHybridCalendar() {
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
        assertEquals(listOf("HYBRID_DIFFERENCE"),
            DocumentDifferential.kernelMessages(ruled, document, dynamic = true).map { it.code })
    }

    /**
     * Every other carrier of a pre-cutover date value is a hybrid instant too, so two of them are compared and
     * counted as instants, never by their labels: a constructed date against a constant in a UTC model, a range
     * written as months and completed in base year 1500, two year-less constants completed there, and a date-time
     * constant shifted across the cutover. Each rule fires on exactly one of the two calendars (KF588).
     */
    @Test
    fun everyPreCutoverValueCountsOnTheHybridCalendar() {
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
        val season = model.dateRange(SEASON)
        val shifted = Dsl.addHours(Dsl.dateTimeValue("04.10.1582T23:00:00"), 1)
        val rules = listOf(
            hybridRule(model, "CONSTRUCTED_EQUALS", leapDay.eq(marchFirst)),
            hybridRule(model, "CONSTRUCTED_BEFORE", leapDay.before(marchFirst)),
            hybridRule(model, "SEASON_ENDS_ON_LEAP_DAY", Dsl.endOfDateRange(season).eq(leapDay)),
            hybridRule(model, "YEARLESS_SPAN", Dsl.differenceInDays(Dsl.dateValue("28.02."), Dsl.dateValue("01.03.")).eq(2)),
            hybridRule(model, "DATE_TIME_SHIFT", Dsl.differenceInDays(shifted, Dsl.dateTimeValue("15.10.1582T12:00:00")).eq(0)),
        )
        val ruled = rules.fold(utc, TypedRules::inject)
        val document = """{"Policy":{"Reference":"P-1","Day":29,"Month":2,"Year":1500,"Season":"02/02"}}"""
        DocumentDifferential.assertAgree(ruled, mapOf("a Julian leap day" to document))
        assertEquals(listOf("CONSTRUCTED_BEFORE", "DATE_TIME_SHIFT", "SEASON_ENDS_ON_LEAP_DAY", "YEARLESS_SPAN"),
            DocumentDifferential.kernelMessages(ruled, document, dynamic = true).map { it.code }.sorted())
    }

    private fun hybridRule(model: BoundModel, code: String, condition: Condition): Rule =
        model.rule(model.group("/Policy")).name(code).errorField(model.str(REFERENCE))
            .`when`(model.str(REFERENCE).filled().and(condition))
            .code(code).severity(Severity.ERROR).message(Dsl.en("Hybrid calendar probe.")).build()

    private companion object {
        const val REFERENCE = "/Policy/Reference"
        const val SEASON = "/Policy/Season"
        const val DAY = "/Policy/Day"
        const val MONTH = "/Policy/Month"
        const val YEAR = "/Policy/Year"
        val ROWS = listOf("1582-10-04", "1582-10-05", "1582-10-14", "1582-10-15", "1500-02-29", "1300-02-29", "1700-02-29", "0000-03-01")
        val RANGE_FLOOR = listOf("datumBereichDatumFalsch")
        val RANGE_MALFORMED = listOf("datumBereichFormatFalsch")
    }
}
