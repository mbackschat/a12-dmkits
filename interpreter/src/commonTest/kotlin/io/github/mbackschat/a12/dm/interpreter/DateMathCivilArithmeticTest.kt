package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.dateParts
import io.github.mbackschat.a12.dm.interpreter.eval.epochDay
import io.github.mbackschat.a12.dm.interpreter.eval.epochDayOfCivil
import io.github.mbackschat.a12.dm.interpreter.eval.isoFromEpochDay
import io.github.mbackschat.a12.dm.interpreter.eval.yearOfEpochDay
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Cross-platform lock for the pure-integer civil-calendar helpers `epochDayOfCivil` / `yearOfEpochDay` — the
 * allocation-free replacements the Europe/Berlin DST offset hot path now uses instead of the string-based
 * `epochDay` / `isoFromEpochDay` (IF128 perf note). Their contract is **bit-identical** to those string forms
 * across the whole date domain; a civil-math slip (a leap year, the Jan/Feb year-shift, a Dec-31→Jan-1 rollover,
 * a negative pre-1970 epoch day, a far-future year) would silently move a DST boundary and only surface at some
 * distant date — so this sweeps broadly rather than pinning the handful of 2024 dates the behaviour tests use.
 */
class DateMathCivilArithmeticTest {

    private fun two(n: Int) = n.toString().padStart(2, '0')
    private fun isoDate(y: Int, mo: Int, d: Int) = y.toString().padStart(4, '0') + "-" + two(mo) + "-" + two(d)

    /** The reference the fast path must reproduce: the string-based `epochDay` over the same 4-digit-year ISO. */
    private fun refEpochDay(y: Int, mo: Int, d: Int): Long = epochDay(isoDate(y, mo, d))!!

    private fun isLeap(y: Int) = y % 4 == 0 && (y % 100 != 0 || y % 400 == 0)

    @Test
    fun epochDayOfCivilMatchesTheStringForm() {
        // Sparse-far + dense-middle years, every month, days INCLUDING 31 (the last-Sunday-of-March/October
        // usage) and the short-month overflow days — both forms compute the same Hinnant value, invalid-day
        // combos included, which is exactly the equivalence that must hold.
        val years = listOf(1, 100, 1583, 1900, 1969, 1970, 1971, 1996, 2000, 2024, 2100, 2200, 9999) +
            (1980..2060).toList()
        for (y in years) for (mo in 1..12) for (d in listOf(1, 15, 28, 30, 31)) {
            assertEquals(refEpochDay(y, mo, d), epochDayOfCivil(y, mo, d), "epochDayOfCivil($y, $mo, $d)")
        }
    }

    @Test
    fun yearOfEpochDayMatchesTheStringForm() {
        // Per-year critical days over a wide span that straddles the 1970 epoch (negative days), each year's
        // boundary (Dec 31 → next Jan 1), and every leap Feb 29.
        for (y in 1900..2200) {
            for ((mo, d) in listOf(1 to 1, 2 to 28, 6 to 15, 12 to 31)) {
                assertEquals(y, yearOfEpochDay(refEpochDay(y, mo, d)), "yearOfEpochDay of ${isoDate(y, mo, d)}")
            }
            if (isLeap(y)) assertEquals(y, yearOfEpochDay(refEpochDay(y, 2, 29)), "yearOfEpochDay of ${isoDate(y, 2, 29)}")
        }
        // Far endpoints of the 4-digit-year domain.
        assertEquals(1, yearOfEpochDay(refEpochDay(1, 1, 1)))
        assertEquals(9999, yearOfEpochDay(refEpochDay(9999, 12, 31)))
    }

    @Test
    fun yearOfEpochDayIsDailyConsistentAcrossBoundaries() {
        // Hammer every single day across a multi-year window so a Dec-31/Jan-1 off-by-one can't hide: the
        // civil year of each epoch day equals the string form's year, day by day.
        val from = refEpochDay(2022, 1, 1)
        val to = refEpochDay(2025, 12, 31)
        var z = from
        while (z <= to) {
            assertEquals(dateParts(isoFromEpochDay(z))!!.first, yearOfEpochDay(z), "yearOfEpochDay($z)")
            z++
        }
    }
}
