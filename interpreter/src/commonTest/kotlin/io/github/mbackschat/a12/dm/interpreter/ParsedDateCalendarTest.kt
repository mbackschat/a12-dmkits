package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.completeFragmentToIso
import io.github.mbackschat.a12.dm.interpreter.eval.intervalEndOfDeclaredFormat
import io.github.mbackschat.a12.dm.interpreter.eval.parseDateTimeToIso
import io.github.mbackschat.a12.dm.interpreter.eval.parseDateToIso
import io.github.mbackschat.a12.dm.interpreter.eval.parseRangeToIso
import io.github.mbackschat.a12.dm.interpreter.eval.partialDateParts
import io.github.mbackschat.a12.dm.interpreter.eval.resolvePartialDate
import io.github.mbackschat.a12.dm.interpreter.model.DatePrecision
import io.github.mbackschat.a12.dm.interpreter.model.ModelTimeZone
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Portable lock for the calendar a parsed date is judged on (KERNEL-FINDINGS KF665): the proleptic one, plus A12's
 * hybrid-parser fallback for a Julian leap day, which reads as its Julian instant under UTC and is discarded under
 * Europe/Berlin. `adapter.laws.StoredDateCalendarDiffTest` holds the same rows to both kernel strategies; here
 * each parse site answers on its own — a `dd.MM.yyyy` date (which a date constant is parsed as), a date-time, a
 * year-less fragment completed in the row's year, a range start and a partially known date — a null meaning no date.
 */
class ParsedDateCalendarTest {

    @Test
    fun everyParseSiteJudgesADayOnTheProlepticCalendarWithTheJulianLeapDayFallback() {
        val observed = ModelTimeZone.entries.associateWith { zone ->
            ROWS.associateWith { iso ->
                val (y, m, d) = iso.split('-')
                listOf(
                    parseDateToIso("$d.$m.$y", "dd.MM.yyyy", zone),
                    parseDateTimeToIso("${iso}T12:00:00", "yyyy-MM-dd'T'HH:mm:ss", zone)?.substringBefore('T'),
                    completeFragmentToIso("$d.$m", "dd.MM", y.toInt(), zone),
                    parseRangeToIso("$iso/1600-01-01", "yyyy-MM-dd", "/", zone, 2000)?.first,
                    partialDateParts("$d.$m.$y", "dd.MM.yyyy", DatePrecision.DAY_OPTIONAL, zone)
                        ?.let { (py, pm, pd) -> "${py.toString().padStart(4, '0')}-${pm.toString().padStart(2, '0')}-${pd.toString().padStart(2, '0')}" },
                )
            }
        }
        val real = { iso: String -> listOf<String?>(iso, iso, iso, iso, iso) }
        val unreal = listOf<String?>(null, null, null, null, null)
        val common = mapOf(
            "1582-10-04" to real("1582-10-04"),
            "1582-10-05" to real("1582-10-05"),
            "1582-10-14" to real("1582-10-14"),
            "1582-10-15" to real("1582-10-15"),
            "1700-02-29" to unreal,
            // A strict `yyyy` parse admits no year 0, at any site. A partially known date's `datumFalsch` for it is
            // the omission rule's verdict on the raw text, not this parse's (`invalidPartialDateOmissionShape`).
            "0000-03-01" to unreal,
        )
        assertEquals(
            mapOf(
                ModelTimeZone.UTC to common + ("1500-02-29" to real("1500-03-10")),
                ModelTimeZone.EUROPE_BERLIN to common + ("1500-02-29" to unreal),
            ).mapValues { (_, rows) -> ROWS.associateWith { rows.getValue(it) } },
            observed,
        )
    }

    /** A component a value omits is completed in the base year and then parsed, so base year 1500 has no February 29. */
    @Test
    fun aPreCutoverBaseYearCompletesOnTheProlepticCalendar() {
        val utc = ModelTimeZone.UTC
        assertEquals("1500-03-10", completeFragmentToIso("29.02", "dd.MM", 1500, utc))
        assertEquals(null, completeFragmentToIso("29.02", "dd.MM", 1500, ModelTimeZone.EUROPE_BERLIN))
        assertEquals("1582-10-05", completeFragmentToIso("05.10", "dd.MM", 1582, utc))
        assertEquals("1500-02-01" to "1500-02-28", parseRangeToIso("02/02", "MM", "/", utc, 1500))
        assertEquals("1500-02-28", intervalEndOfDeclaredFormat("1500-02-01", "MM"))
        assertEquals("1500-02-28", resolvePartialDate("00.02.1500", "dd.MM.yyyy", DatePrecision.DAY_OPTIONAL, lastDay = true, utc))
        assertEquals("1600-02-29", resolvePartialDate("00.02.1600", "dd.MM.yyyy", DatePrecision.DAY_OPTIONAL, lastDay = true, utc))
    }

    /** A parsed Berlin wall time inside a spring-forward gap reads as the wall clock of its instant, one gap later. */
    @Test
    fun aParsedGapWallTimeMovesForwardByTheGap() {
        val format = "yyyy-MM-dd'T'HH:mm:ss"
        assertEquals(
            listOf("2024-03-31T03:30:00", "1945-05-24T03:30:00", "1893-04-01T00:06:32", "2024-03-31T02:30:00"),
            listOf(
                parseDateTimeToIso("2024-03-31T02:30:00", format, ModelTimeZone.EUROPE_BERLIN),
                parseDateTimeToIso("1945-05-24T02:30:00", format, ModelTimeZone.EUROPE_BERLIN),
                parseDateTimeToIso("1893-04-01T00:00:00", format, ModelTimeZone.EUROPE_BERLIN),
                parseDateTimeToIso("2024-03-31T02:30:00", format, ModelTimeZone.UTC),
            ),
        )
    }

    private companion object {
        val ROWS = listOf("1582-10-04", "1582-10-05", "1582-10-14", "1582-10-15", "1500-02-29", "1700-02-29", "0000-03-01")
    }
}
