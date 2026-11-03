package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.differenceInDays
import io.github.mbackschat.a12.dm.interpreter.eval.differenceInMonths
import io.github.mbackschat.a12.dm.interpreter.eval.differenceInYears
import io.github.mbackschat.a12.dm.interpreter.eval.epochDay
import io.github.mbackschat.a12.dm.interpreter.eval.isConstructibleDate
import io.github.mbackschat.a12.dm.interpreter.eval.isoFromZonedInstant
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.eval.plusLocalDays
import io.github.mbackschat.a12.dm.interpreter.eval.shiftMonths
import io.github.mbackschat.a12.dm.interpreter.eval.shiftYears
import io.github.mbackschat.a12.dm.interpreter.eval.startOfDayMillis
import io.github.mbackschat.a12.dm.interpreter.eval.zonedInstantOf
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.ModelTimeZone
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Portable lock for the proleptic calendar of the `Date(...)` constructor (`LocalDate.of` on kernel 31.1.1) and of the
 * date shifts and differences that consume it (KERNEL-FINDINGS KF665). Parsing judges reality on the same calendar,
 * with one fallback, which `ParsedDateCalendarTest` locks.
 */
class ConstructedDateCalendarTest {

    @Test
    fun theConstructorIsProleptic() {
        assertFalse(isConstructibleDate(1500, 2, 29), "a Julian leap day is no proleptic day")
        assertTrue(isConstructibleDate(1600, 2, 29), "a Gregorian leap day")
        assertFalse(isConstructibleDate(1700, 2, 29), "a Gregorian common century year")
        assertTrue(isConstructibleDate(1582, 10, 5), "the labels the hybrid calendar skipped are days")
        assertTrue(isConstructibleDate(1582, 10, 14), "the labels the hybrid calendar skipped are days")
        assertFalse(isConstructibleDate(2024, 4, 31), "an impossible day")
    }

    private val utc = ModelTimeZone.UTC
    private fun date(iso: String): Long = startOfDayMillis(epochDay(iso)!!, utc)
    private fun dateTime(iso: String): Long = zonedInstantOf(iso, utc)!! * 1_000L
    private fun label(millis: Long?): String? = millis?.let { isoFromZonedInstant(it / 1_000L, utc) }

    /** `ZonedDateTime.plus*` and A12's own February-28 corrections, on the proleptic calendar. */
    @Test
    fun shiftsAreJavaTimeArithmeticWithA12sFebruary28Corrections() {
        assertEquals("1582-10-05T00:00:00", label(plusLocalDays(date("1582-10-04"), 1, utc)))
        assertEquals("2024-02-29T00:00:00", label(shiftMonths(date("2024-01-31"), 1, utc)), "the day clamps")
        assertEquals("2025-02-28T00:00:00", label(shiftYears(date("2024-02-29"), 1, utc)), "February 29 clamps")
        assertEquals("2024-02-29T00:00:00", label(shiftYears(date("2023-02-28"), 1, utc)),
            "a common-year 28th lands on the leap year's 29th")
        assertEquals("2025-02-28T00:00:00", label(shiftYears(dateTime("2024-02-28T10:00:00"), 1, utc)),
            "a leap-year 28th landing on a common year drops its time of day")
        assertEquals("2025-02-28T00:00:00", label(shiftMonths(dateTime("2024-02-28T10:00:00"), 12, utc)),
            "the month shift carries the same reset")
        assertEquals("2024-03-28T10:00:00", label(shiftMonths(dateTime("2024-02-28T10:00:00"), 1, utc)),
            "but only onto a common year's 28th")
    }

    @Test
    fun differencesCountOnTheProlepticCalendar() {
        assertEquals(11, differenceInDays(date("1582-10-04"), date("1582-10-15"), utc), "no skipped labels")
        assertEquals(3_652_058, differenceInDays(date("0001-01-01"), date("9999-12-31"), utc), "the whole label domain")
        assertEquals(2, differenceInMonths(date("2008-01-31"), date("2008-03-31"), utc),
            "months count on a fresh copy of the earlier date, so 31.01 + 2 months is 31.03")
        assertEquals(1, differenceInMonths(date("2008-01-31"), date("2008-02-29"), utc))
        assertEquals(1, differenceInYears(date("2023-02-28"), date("2024-02-29"), utc))
        assertEquals(-1, differenceInYears(date("2024-02-29"), date("2023-02-28"), utc), "authored order restores the sign")
        assertEquals(0, differenceInYears(dateTime("2023-06-01T10:00:00"), dateTime("2024-06-01T09:00:00"), utc),
            "the time of day counts")
    }

    @Test
    fun unrealConstructedDateAndDateTimeExtractorsReturnZeroButMalformedPartsStayUnknown() {
        val date = "Date($DAY, $MONTH, $YEAR)"
        val time = "Time($HOUR, $MINUTE, $SECOND)"
        assertTrue(fires("YearFromDate($date) == 0", "31"))
        assertTrue(fires("QuarterFromDate($date) == 0", "31"))
        assertTrue(fires("""HoursFromTime(DateTime($date, Time("12", "00", "00"))) == 0""", "31"))
        assertTrue(fires("DifferenceInDays($date, $date) == 0", "31"))
        assertTrue(fires(
            """DifferenceInHours(DateTime($date, Time("12", "00", "00")), """ +
                """DateTime($date, Time("12", "00", "00"))) == 0""",
            "31",
        ))
        assertFalse(fires("YearFromDate($date) == 0", "bad"))
        assertFalse(fires("QuarterFromDate($date) == 0", "bad"))
        assertFalse(fires("DifferenceInDays($date, $date) == 0", "bad"))

        assertTrue(fires("YearFromDate(AddDays($date, 1)) == 0", "31"),
            "a legal date shift must retain the construction's valueless reason")
        assertTrue(fires("YearFromDate(DateFromDateTime(DateTime($date, $time))) == 0", "31", "12"),
            "DateTime and DateFromDateTime must retain the construction's valueless reason")
        assertTrue(fires("YearFromDate(DateTime($date, $time)) == 0", "15", null),
            "an empty time makes an otherwise-real DateTime present-but-valueless")
        assertFalse(fires("YearFromDate(DateTime($date, $time)) == 0", null, "bad"),
            "a malformed time is non-relevant and must beat the date's incomplete state")
    }

    private fun fires(condition: String, day: String?, hour: String? = "12"): Boolean {
        val model = ModelDef(
            fields = listOf(
                FieldDef(DAY, Kind.NUMBER, scale = 0),
                FieldDef(MONTH, Kind.NUMBER, scale = 0),
                FieldDef(YEAR, Kind.NUMBER, scale = 0),
                FieldDef(HOUR, Kind.NUMBER, scale = 0),
                FieldDef(MINUTE, Kind.NUMBER, scale = 0),
                FieldDef(SECOND, Kind.NUMBER, scale = 0),
            ),
            rules = listOf(RuleDef("/Calendar/R", "/Calendar", condition, "DATE", "../Year")),
        )
        val document = model.newDocument().group("/Calendar", 1)
            .field(MONTH, "4", 1, 1)
            .field(YEAR, "2024", 1, 1)
            .field(MINUTE, "0", 1, 1)
            .field(SECOND, "0", 1, 1)
        day?.let { document.field(DAY, it, 1, 1) }
        hour?.let { document.field(HOUR, it, 1, 1) }
        return DmInterpreter(model).validateFull(document).any { it.code == "DATE" }
    }

    private companion object {
        const val DAY = "/Calendar/Day"
        const val MONTH = "/Calendar/Month"
        const val YEAR = "/Calendar/Year"
        const val HOUR = "/Calendar/Hour"
        const val MINUTE = "/Calendar/Minute"
        const val SECOND = "/Calendar/Second"
    }
}
