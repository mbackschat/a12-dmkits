package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.completeFragmentToIso
import io.github.mbackschat.a12.dm.interpreter.eval.parseDateTimeToIso
import io.github.mbackschat.a12.dm.interpreter.eval.parseDateToIso
import io.github.mbackschat.a12.dm.interpreter.eval.parseRangeToIso
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Portable lock for the calendar a parsed date is judged on (KERNEL-FINDINGS KF588): the hybrid one, at every parse
 * site, so the JVM and Node read the same days. `adapter.laws.StoredDateHybridCalendarDiffTest` holds the same rows to
 * both kernel strategies; here each site answers on its own — a `dd.MM.yyyy` date, a date-time, a year-less fragment completed in the row's year, and a range start — a null meaning no date.
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
            )
        }
        val real = { iso: String -> listOf<String?>(iso, iso, iso, iso) }
        val unreal = listOf<String?>(null, null, null, null)
        assertEquals(
            mapOf(
                "1582-10-04" to real("1582-10-04"),
                "1582-10-05" to unreal,
                "1582-10-14" to unreal,
                "1582-10-15" to real("1582-10-15"),
                "1500-02-29" to real("1500-02-29"),
                "1700-02-29" to unreal,
            ),
            observed,
        )
    }

    private companion object {
        val ROWS = listOf("1582-10-04", "1582-10-05", "1582-10-14", "1582-10-15", "1500-02-29", "1700-02-29")
    }
}
