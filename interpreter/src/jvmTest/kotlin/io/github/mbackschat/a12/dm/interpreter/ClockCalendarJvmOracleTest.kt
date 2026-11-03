package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.differenceInDays
import io.github.mbackschat.a12.dm.interpreter.eval.isoFromZonedInstant
import io.github.mbackschat.a12.dm.interpreter.eval.localToInstant
import io.github.mbackschat.a12.dm.interpreter.eval.offsetSeconds
import io.github.mbackschat.a12.dm.interpreter.eval.plusLocalDays
import io.github.mbackschat.a12.dm.interpreter.eval.plusLocalMonths
import io.github.mbackschat.a12.dm.interpreter.eval.plusLocalYears
import io.github.mbackschat.a12.dm.interpreter.model.EvalClock
import io.github.mbackschat.a12.dm.interpreter.model.ModelTimeZone
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * KF665 — the interpreter's zone and calendar primitives, each against the `java.time` operation kernel 31.1.1 calls.
 *
 * A12 reads every date as `instant.atZone(zone)` and parses, constructs and shifts through `ZonedDateTime`, so
 * `java.time` itself is the oracle for the offset, the wall-clock label, `ofLocal` resolution (with and without a
 * preferred offset), `plusDays`/`plusMonths`/`plusYears` and `ChronoUnit.DAYS.between`. A12's own corrections on top
 * of these (February 28, the month/year difference loops) are its code, not `java.time`'s, and are held to the kernel
 * by the adapter differentials instead. JVM-only on purpose: every oracle is a JDK class.
 */
class ClockCalendarJvmOracleTest {

    private val zones = mapOf(ModelTimeZone.UTC to ZoneId.of("UTC"), ModelTimeZone.EUROPE_BERLIN to ZoneId.of("Europe/Berlin"))

    /** Instants across 1 CE to 2600 CE, densest around Berlin's 1893 switch from local mean time and its transitions. */
    private val instants: List<Long> = buildList {
        val random = Random(665)
        repeat(4_000) { add(random.nextLong(-62_135_596_800L, 20_000_000_000L)) }
        val berlinZoneSwitch = Instant.parse("1893-03-31T23:06:32Z").epochSecond
        for (offset in -6L..6L) add(berlinZoneSwitch + offset * 120L)
        for (transition in ZoneId.of("Europe/Berlin").rules.transitions.take(80)) {
            for (offset in -3L..3L) add(transition.toEpochSecond() + offset * 1_200L)
        }
    }

    /** Wall times clustered on every Berlin transition's gap or overlap, plus random ones. */
    private val locals: List<LocalDateTime> = buildList {
        val random = Random(1665)
        repeat(2_000) { add(LocalDateTime.ofEpochSecond(random.nextLong(-62_135_596_800L, 20_000_000_000L), 0, ZoneOffset.UTC)) }
        for (transition in ZoneId.of("Europe/Berlin").rules.transitions.take(80)) {
            for (minutes in -90L..150L step 15L) add(transition.dateTimeBefore.plusMinutes(minutes))
        }
    }

    private val label = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")

    @Test
    fun theOffsetAndTheLabelAreZonedDateTimes() {
        val wrong = zones.flatMap { (zone, id) ->
            instants.mapNotNull { second ->
                val zoned = Instant.ofEpochSecond(second).atZone(id)
                val expected = zoned.format(label) to zoned.offset.totalSeconds.toLong()
                val actual = isoFromZonedInstant(second, zone) to offsetSeconds(second, zone)
                "$id $second: $actual, java.time $expected".takeIf { actual != expected }
            }
        }
        assertEquals(emptyList(), wrong)
    }

    @Test
    fun aWallTimeResolvesAsZonedDateTimeOfLocal() {
        val wrong = zones.flatMap { (zone, id) ->
            locals.flatMap { local ->
                val naive = local.toEpochSecond(ZoneOffset.UTC)
                val plain = ZonedDateTime.ofLocal(local, id, null).toEpochSecond() to localToInstant(naive, zone)
                val preferences = id.rules.getValidOffsets(local).map { preferred ->
                    ZonedDateTime.ofLocal(local, id, preferred).toEpochSecond() to
                        localToInstant(naive, zone, preferred.totalSeconds.toLong())
                }
                (listOf(plain) + preferences).filter { (expected, actual) -> expected != actual }
                    .map { (expected, actual) -> "$id $local: $actual, java.time $expected" }
            }
        }
        assertEquals(emptyList(), wrong)
    }

    @Test
    fun theWallClockShiftsArePlusDaysMonthsAndYears() {
        val random = Random(2665)
        val wrong = zones.flatMap { (zone, id) ->
            instants.filter { it in -62_000_000_000L..15_000_000_000L }.flatMap { second ->
                val zoned = Instant.ofEpochSecond(second).atZone(id)
                val millis = second * 1_000L
                val days = random.nextLong(-40_000L, 40_000L)
                val months = random.nextLong(-1_200L, 1_200L)
                val years = random.nextLong(-100L, 100L)
                listOf(
                    "plusDays($days)" to (zoned.plusDays(days).toEpochSecond() * 1_000L to plusLocalDays(millis, days, zone)),
                    "plusMonths($months)" to (zoned.plusMonths(months).toEpochSecond() * 1_000L to plusLocalMonths(millis, months, zone)),
                    "plusYears($years)" to (zoned.plusYears(years).toEpochSecond() * 1_000L to plusLocalYears(millis, years, zone)),
                ).filter { (_, pair) -> pair.first != pair.second }
                    .map { (op, pair) -> "$id $zoned $op: ${pair.second}, java.time ${pair.first}" }
            }
        }
        assertEquals(emptyList(), wrong)
    }

    @Test
    fun theDayDifferenceIsChronoUnitDaysOverOrderedOperands() {
        val random = Random(3665)
        val wrong = zones.flatMap { (zone, id) ->
            instants.mapNotNull { a ->
                val b = a + random.nextLong(-400L * 86_400L, 400L * 86_400L)
                val za = Instant.ofEpochSecond(a).atZone(id)
                val zb = Instant.ofEpochSecond(b).atZone(id)
                // A12 orders the operands by instant and reapplies the sign (`getDateDiff`).
                val expected = if (za < zb) ChronoUnit.DAYS.between(za, zb) else -ChronoUnit.DAYS.between(zb, za)
                val actual = differenceInDays(a * 1_000L, b * 1_000L, zone)
                "$id $za → $zb: $actual, java.time $expected".takeIf { actual != expected }
            }
        }
        assertEquals(emptyList(), wrong)
    }

    @Test
    fun theClockLabelsAreTheWallClockOfTheInstant() {
        val wrong = zones.flatMap { (zone, id) ->
            instants.mapNotNull { second ->
                val millis = second * 1_000L + 250L
                val wall = Instant.ofEpochMilli(millis).atZone(id).toLocalDateTime()
                val expected = EvalClock(wall.toLocalDate().toString(), "%sT%02d:%02d:%02d.250".format(
                    wall.toLocalDate(), wall.hour, wall.minute, wall.second))
                val actual = EvalClock.at(millis, zone)
                "$id $second: $actual, expected $expected".takeIf { actual != expected }
            }
        }
        assertEquals(emptyList(), wrong)
    }
}
