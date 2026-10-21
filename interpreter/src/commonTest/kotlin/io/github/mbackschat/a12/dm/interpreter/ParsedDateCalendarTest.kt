package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.completeFragmentToIso
import io.github.mbackschat.a12.dm.interpreter.eval.intervalEndOfDeclaredFormat
import io.github.mbackschat.a12.dm.interpreter.eval.parseDateTimeToIso
import io.github.mbackschat.a12.dm.interpreter.eval.parseDateToIso
import io.github.mbackschat.a12.dm.interpreter.eval.parseRangeToIso
import io.github.mbackschat.a12.dm.interpreter.eval.partialDateParts
import io.github.mbackschat.a12.dm.interpreter.eval.resolvePartialDate
import io.github.mbackschat.a12.dm.interpreter.model.DatePrecision
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Portable lock for the calendar a parsed date is judged on (KERNEL-FINDINGS KF588): the hybrid one, at every parse
 * site, so the JVM and Node read the same days. `adapter.laws.StoredDateHybridCalendarDiffTest` holds the same rows to
 * both kernel strategies; here each site answers on its own — a `dd.MM.yyyy` date (which a date constant is parsed
 * as), a date-time, a year-less fragment completed in the row's year, a range start and a partially known date — a
 * null meaning no date.
 */
class ParsedDateCalendarTest {

    @Test
    fun everyParseSiteJudgesADayOnTheHybridCalendar() {
        val observed = ROWS.associateWith { iso ->
            val (y, m, d) = iso.split('-')
            listOf(
                parseDateToIso("$d.$m.$y", "dd.MM.yyyy"),
                parseDateTimeToIso("${iso}T12:00:00", "yyyy-MM-dd'T'HH:mm:ss")?.substringBefore('T'),
                completeFragmentToIso("$d.$m", "dd.MM", y.toInt()),
                parseRangeToIso("$iso/1600-01-01", "yyyy-MM-dd", "/", 2000)?.first,
                partialDateParts("$d.$m.$y", "dd.MM.yyyy", DatePrecision.DAY_OPTIONAL)
                    ?.let { (py, pm, pd) -> "${py.toString().padStart(4, '0')}-${pm.toString().padStart(2, '0')}-${pd.toString().padStart(2, '0')}" },
            )
        }
        val real = { iso: String -> listOf<String?>(iso, iso, iso, iso, iso) }
        val unreal = listOf<String?>(null, null, null, null, null)
        assertEquals(
            mapOf(
                "1582-10-04" to real("1582-10-04"),
                "1582-10-05" to unreal,
                "1582-10-14" to unreal,
                "1582-10-15" to real("1582-10-15"),
                "1500-02-29" to real("1500-02-29"),
                "1700-02-29" to unreal,
                // No calendar A12 reads has a year 0, at any site. A partially known date's `datumFalsch` for it
                // is the omission rule's verdict on the raw text, not this parse's (`invalidPartialDateOmissionShape`).
                "0000-03-01" to unreal,
            ),
            observed,
        )
    }

    /** A component a value omits is completed in the base year on the same calendar, so 1500 is a Julian leap year. */
    @Test
    fun aPreCutoverBaseYearCompletesOnTheHybridCalendar() {
        assertEquals("1500-02-29", completeFragmentToIso("29.02", "dd.MM", 1500))
        assertEquals(null, completeFragmentToIso("05.10", "dd.MM", 1582))
        assertEquals("1500-02-01" to "1500-02-29", parseRangeToIso("02/02", "MM", "/", 1500))
        assertEquals("1500-02-29", intervalEndOfDeclaredFormat("1500-02-01", "MM"))
        assertEquals("1500-02-29", resolvePartialDate("00.02.1500", "dd.MM.yyyy", DatePrecision.DAY_OPTIONAL, lastDay = true))
        assertEquals("1700-02-28", resolvePartialDate("00.02.1700", "dd.MM.yyyy", DatePrecision.DAY_OPTIONAL, lastDay = true))
    }

    private companion object {
        val ROWS = listOf("1582-10-04", "1582-10-05", "1582-10-14", "1582-10-15", "1500-02-29", "1700-02-29", "0000-03-01")
    }
}
