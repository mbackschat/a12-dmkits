package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.differenceInCalendarDays
import io.github.mbackschat.a12.dm.interpreter.model.ModelTimeZone
import kotlin.test.Test
import kotlin.test.assertEquals

/** Portable lock for `DifferenceInDays` calendar-step semantics at the operation's own runtime seam. */
class CalendarStepDifferenceInDaysTest {

    @Test
    fun berlinGapAdjacentLandingSeparatesCalendarStepsFromWallSeconds() {
        val zone = ModelTimeZone.EUROPE_BERLIN
        assertEquals(
            1,
            differenceInCalendarDays("2024-03-30T02:30:00", "2024-03-31T01:45:00", zone),
            "one Calendar.DAY_OF_MONTH step lands at 01:30, before the 01:45 endpoint",
        )
        assertEquals(
            0,
            differenceInCalendarDays("2024-03-30T02:30:00", "2024-03-31T01:29:59", zone),
            "the neighboring endpoint before the 01:30 landing is still zero steps",
        )
        assertEquals(
            -1,
            differenceInCalendarDays("2024-03-31T01:45:00", "2024-03-30T02:30:00", zone),
            "authored operand order restores the sign",
        )
        assertEquals(
            2,
            differenceInCalendarDays("2024-03-30T02:30:00", "2024-04-01T02:00:00", zone),
            "the 01:30 gap landing persists into the following stateful +1 step",
        )
    }

    @Test
    fun utcAndOrdinaryBerlinControlsStayTimeOfDayAware() {
        assertEquals(
            0,
            differenceInCalendarDays(
                "2024-03-30T02:30:00",
                "2024-03-31T01:45:00",
                ModelTimeZone.UTC,
            ),
            "without a zone transition the next same-clock landing is still after the endpoint",
        )
        for (zone in ModelTimeZone.entries) {
            assertEquals(
                0,
                differenceInCalendarDays("2024-06-15T23:00:00", "2024-06-16T01:00:00", zone),
                "$zone two-hour cross-midnight control",
            )
            assertEquals(
                1,
                differenceInCalendarDays("2024-06-15T12:00:00", "2024-06-16T12:00:00", zone),
                "$zone same-clock next-day control",
            )
        }
    }
}
