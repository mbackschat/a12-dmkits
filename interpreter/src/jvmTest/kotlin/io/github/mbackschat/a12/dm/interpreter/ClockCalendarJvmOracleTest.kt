package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.DateCalendarBasis
import io.github.mbackschat.a12.dm.interpreter.eval.isoFromCalendarInstant
import io.github.mbackschat.a12.dm.interpreter.model.EvalClock
import io.github.mbackschat.a12.dm.interpreter.model.ModelTimeZone
import java.time.Instant
import java.time.ZoneOffset
import java.util.Calendar
import java.util.Date
import java.util.GregorianCalendar
import java.util.TimeZone
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * KF576 — the two readings of a clock instant, each against the implementation that defines it.
 *
 * [EvalClock.at]'s labels are proleptic dates at the wall offset A12's `java.util.TimeZone` gives, and the hybrid
 * re-reading that `Today` and `Now` take on the temporal path is `GregorianCalendar` in the model's zone, which is
 * what A12 sets to its clock. The offset is `java.util`'s, not `java.time`'s: before 1893 `java.time` applies
 * Berlin's local mean time, +0:53:28, where `java.util.TimeZone` applies +1:00. JVM-only on purpose: every oracle
 * is a JDK class.
 */
class ClockCalendarJvmOracleTest {

    private val zones = mapOf(ModelTimeZone.UTC to "UTC", ModelTimeZone.EUROPE_BERLIN to "Europe/Berlin")

    /** Instants across 1 CE to 2600 CE, densest around the 1582 cutover and Berlin's 1893 switch from local mean time. */
    private val instants: List<Long> = buildList {
        val random = Random(576)
        repeat(4_000) { add(random.nextLong(-62_135_596_800L, 20_000_000_000L)) }
        val cutover = Instant.parse("1582-10-15T00:00:00Z").epochSecond
        for (offset in -40L..40L) add(cutover + offset * 43_200L + 1_234L)
        val berlinZoneSwitch = Instant.parse("1893-04-01T00:00:00Z").epochSecond
        for (offset in -6L..6L) add(berlinZoneSwitch + offset * 1_800L)
    }

    @Test
    fun theHybridReadingIsGregorianCalendarInTheModelZone() {
        val wrong = zones.flatMap { (zone, id) ->
            instants.mapNotNull { second ->
                val calendar = GregorianCalendar(TimeZone.getTimeZone(id)).apply { time = Date(second * 1_000L) }
                val expected = "%04d-%02d-%02dT%02d:%02d:%02d".format(
                    calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH) + 1, calendar.get(Calendar.DAY_OF_MONTH),
                    calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE), calendar.get(Calendar.SECOND),
                )
                val actual = isoFromCalendarInstant(second, zone, DateCalendarBasis.LEGACY_HYBRID)
                "$id $second: $actual, GregorianCalendar $expected".takeIf { actual != expected && calendar.get(Calendar.ERA) == GregorianCalendar.AD }
            }
        }
        assertEquals(emptyList(), wrong)
    }

    @Test
    fun theClockLabelsAreProlepticDatesAtA12sWallOffset() {
        val wrong = zones.flatMap { (zone, id) ->
            instants.mapNotNull { second ->
                val millis = second * 1_000L + 250L
                val offset = TimeZone.getTimeZone(id).getOffset(millis).toLong()
                val wall = Instant.ofEpochMilli(millis + offset).atZone(ZoneOffset.UTC).toLocalDateTime()
                val expected = EvalClock(wall.toLocalDate().toString(), "%sT%02d:%02d:%02d.250".format(
                    wall.toLocalDate(), wall.hour, wall.minute, wall.second))
                val actual = EvalClock.at(millis, zone)
                "$id $second: $actual, expected $expected".takeIf { actual != expected }
            }
        }
        assertEquals(emptyList(), wrong)
    }
}
