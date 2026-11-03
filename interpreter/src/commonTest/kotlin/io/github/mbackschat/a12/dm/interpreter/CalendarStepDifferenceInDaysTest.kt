package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.differenceInDays
import io.github.mbackschat.a12.dm.interpreter.eval.zonedInstantOf
import io.github.mbackschat.a12.dm.interpreter.model.ModelTimeZone
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Portable lock for `DifferenceInDays` at the operation's own runtime seam: complete days between the two model-zone
 * WALL CLOCKS, as `ChronoUnit.DAYS.between` counts them on kernel 31.1.1 (KERNEL-FINDINGS KF665). The Berlin rows
 * are the ones where the 30.8.1 stateful `Calendar` day loop answered differently.
 */
class CalendarStepDifferenceInDaysTest {

    private fun days(a: String, b: String, zone: ModelTimeZone): Long =
        differenceInDays(zonedInstantOf(a, zone)!! * 1_000L, zonedInstantOf(b, zone)!! * 1_000L, zone)

    @Test
    fun aBerlinGapAdjacentPairCountsWallClocks() {
        val zone = ModelTimeZone.EUROPE_BERLIN
        assertEquals(0, days("2024-03-30T02:30:00", "2024-03-31T01:45:00", zone),
            "the later wall clock 01:45 has not reached 02:30, so no complete day")
        assertEquals(0, days("2024-03-31T01:45:00", "2024-03-30T02:30:00", zone), "reversed, still none")
        assertEquals(1, days("2024-03-30T02:30:00", "2024-04-01T02:00:00", zone),
            "two dates apart, the later clock short of 02:30: one complete day")
        assertEquals(1, days("2024-03-30T02:30:00", "2024-03-31T02:30:00", zone),
            "a gap endpoint resolves to 03:30, past 02:30: one complete day")
        assertEquals(7671, days("2003-03-31T02:30:00", "2024-03-31T03:00:00", zone),
            "the long spring candidate is the plain wall-clock count")
    }

    @Test
    fun utcAndOrdinaryBerlinControlsStayTimeOfDayAware() {
        for (zone in ModelTimeZone.entries) {
            assertEquals(0, days("2024-06-15T23:00:00", "2024-06-16T01:00:00", zone), "$zone two-hour cross-midnight control")
            assertEquals(1, days("2024-06-15T12:00:00", "2024-06-16T12:00:00", zone), "$zone same-clock next-day control")
            assertEquals(-1, days("2024-06-16T12:00:00", "2024-06-15T12:00:00", zone), "$zone authored order restores the sign")
        }
    }
}
