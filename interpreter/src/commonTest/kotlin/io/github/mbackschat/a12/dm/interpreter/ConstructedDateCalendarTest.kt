package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.isConstructibleDate
import io.github.mbackschat.a12.dm.interpreter.eval.DateCalendarBasis
import io.github.mbackschat.a12.dm.interpreter.eval.addLegacyCalendarDays
import io.github.mbackschat.a12.dm.interpreter.eval.addLegacyCalendarMonths
import io.github.mbackschat.a12.dm.interpreter.eval.addLegacyCalendarYears
import io.github.mbackschat.a12.dm.interpreter.eval.calendarOperandInstant
import io.github.mbackschat.a12.dm.interpreter.eval.calendarDayDifferenceDetail
import io.github.mbackschat.a12.dm.interpreter.eval.differenceInCalendarDays
import io.github.mbackschat.a12.dm.interpreter.eval.differenceInLegacyCalendarMonths
import io.github.mbackschat.a12.dm.interpreter.eval.differenceInLegacyCalendarYears
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
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
 * Portable lock for the legacy-hybrid calendar reality used only by the `Date(...)` constructor.
 * Stored dates and the other date consumers retain their separately specified parsing/arithmetic rules.
 */
class ConstructedDateCalendarTest {

    @Test
    fun julianLeapDayBeforeTheDefaultCutoverIsReal() {
        for (zone in ModelTimeZone.entries) {
            assertTrue(isConstructibleDate(1500, 2, 29, zone), "$zone must admit the Julian leap day")
            assertFalse(isConstructibleDate(1500, 2, 30, zone), "$zone must still reject an impossible day")
            assertFalse(isConstructibleDate(1501, 2, 29, zone), "$zone must reject a non-leap Julian year")
        }
    }

    @Test
    fun defaultGregorianCutoverSkipsOctoberFifthThroughFourteenth() {
        for (zone in ModelTimeZone.entries) {
            assertTrue(isConstructibleDate(1582, 10, 4, zone), "$zone Julian-side neighbor")
            assertFalse(isConstructibleDate(1582, 10, 5, zone), "$zone first skipped label")
            assertFalse(isConstructibleDate(1582, 10, 14, zone), "$zone last skipped label")
            assertTrue(isConstructibleDate(1582, 10, 15, zone), "$zone Gregorian-side neighbor")
        }
    }

    @Test
    fun dateConsumersRetainTheLegacyCalendarValue() {
        assertEquals("1500-03-01", addLegacyCalendarDays("1500-02-29", 1))
        assertEquals("1500-03-29", addLegacyCalendarMonths("1500-02-29", 1))
        assertEquals("1501-02-28", addLegacyCalendarYears("1500-02-29", 1))
        assertEquals(1, differenceInLegacyCalendarMonths("1500-02-29", "1500-03-29"))
        assertEquals(1, differenceInLegacyCalendarYears("1500-02-29", "1501-02-28"))
        assertEquals("1582-10-15", addLegacyCalendarDays("1582-10-04", 1))
        assertEquals("1582-10-20", addLegacyCalendarMonths("1582-09-10", 1))
        assertEquals("1582-10-20", addLegacyCalendarYears("1581-10-10", 1))
        assertEquals(0, differenceInLegacyCalendarMonths("1582-09-10", "1582-10-15"))
        assertEquals(1, differenceInLegacyCalendarMonths("1582-09-10", "1582-10-20"))
        assertEquals(0, differenceInLegacyCalendarYears("1581-10-10", "1582-10-15"))
        assertEquals(11, differenceInLegacyCalendarMonths("1581-10-10", "1582-10-15"))
        assertEquals(1, differenceInLegacyCalendarYears("1581-10-10", "1582-10-20"))
        assertEquals(12, differenceInLegacyCalendarMonths("1581-10-10", "1582-10-20"))
        assertEquals("1500-02-28", addLegacyCalendarYears("1499-02-28", 1))
    }

    @Test
    fun calendarDayDifferenceHandlesTheCutoverAndBoundsTheFullDomainResidual() {
        val basis = DateCalendarBasis.LEGACY_HYBRID
        val cutoverStart = calendarOperandInstant("1582-10-04", ModelTimeZone.UTC, basis)!!
        val cutoverEnd = calendarOperandInstant("1582-10-15", ModelTimeZone.UTC, basis)!!
        assertEquals(1, differenceInCalendarDays(cutoverStart, cutoverEnd, ModelTimeZone.UTC))

        val domainStart = calendarOperandInstant("0001-01-01", ModelTimeZone.UTC, basis)!!
        val domainEnd = calendarOperandInstant("9999-12-31", ModelTimeZone.UTC, basis)!!
        val detail = calendarDayDifferenceDetail(domainStart, domainEnd, ModelTimeZone.UTC)
        assertEquals(3_652_060, detail.days)
        assertTrue(detail.residualSteps < 2_800, "the year seed must bound, not eliminate, residual +1 steps")
    }

    @Test
    fun unrealConstructedDateAndDateTimeExtractorsReturnZeroButMalformedPartsStayUnknown() {
        val date = "Date($DAY, $MONTH, $YEAR)"
        val time = "Time($HOUR, $MINUTE, $SECOND)"
        assertTrue(fires("YearFromDate($date) == 0", "10"))
        assertTrue(fires("QuarterFromDate($date) == 0", "10"))
        assertTrue(fires("""HoursFromTime(DateTime($date, Time("12", "00", "00"))) == 0""", "10"))
        assertTrue(fires("DifferenceInDays($date, $date) == 0", "10"))
        assertTrue(fires(
            """DifferenceInHours(DateTime($date, Time("12", "00", "00")), """ +
                """DateTime($date, Time("12", "00", "00"))) == 0""",
            "10",
        ))
        assertFalse(fires("YearFromDate($date) == 0", "bad"))
        assertFalse(fires("QuarterFromDate($date) == 0", "bad"))
        assertFalse(fires("DifferenceInDays($date, $date) == 0", "bad"))

        assertTrue(fires("YearFromDate(AddDays($date, 1)) == 0", "10"),
            "a legal date shift must retain the construction's valueless reason")
        assertTrue(fires("YearFromDate(DateFromDateTime(DateTime($date, $time))) == 0", "10", "12"),
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
            .field(MONTH, "10", 1, 1)
            .field(YEAR, "1582", 1, 1)
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
