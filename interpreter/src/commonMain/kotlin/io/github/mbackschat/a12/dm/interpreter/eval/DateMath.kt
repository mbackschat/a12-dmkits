package io.github.mbackschat.a12.dm.interpreter.eval

/**
 * Pure date arithmetic on ISO `yyyy-MM-dd` strings — no `java.time`/luxon, so it stays in commonMain and
 * runs identically on JVM and JS (the date shim the proposal §5 anticipated isn't needed for whole-date
 * differences). [epochDay] is the proleptic-Gregorian day number (days since 1970-01-01), which makes a
 * day difference a plain subtraction.
 */
private val ISO = Regex("""(\d{4})-(\d{2})-(\d{2})""")

/** The (year, month, day) of an ISO `yyyy-MM-dd` date, or null if malformed — for the *FromDate extractors. */
fun dateParts(iso: String): Triple<Int, Int, Int>? {
    val m = ISO.matchEntire(iso) ?: return null
    val mo = m.groupValues[2].toInt()
    val d = m.groupValues[3].toInt()
    if (mo < 1 || mo > 12 || d < 1 || d > 31) return null
    return Triple(m.groupValues[1].toInt(), mo, d)
}

/** Days since 1970-01-01 for an ISO date, or null if it is not a well-formed `yyyy-MM-dd`. */
fun epochDay(iso: String): Long? {
    val m = ISO.matchEntire(iso) ?: return null
    val y = m.groupValues[1].toLong()
    val mo = m.groupValues[2].toInt()
    val d = m.groupValues[3].toLong()
    if (mo < 1 || mo > 12 || d < 1 || d > 31) return null
    // Howard Hinnant's days_from_civil: shift the year so March is month 0 (leap day lands last).
    val yAdj = if (mo <= 2) y - 1 else y
    val era = (if (yAdj >= 0) yAdj else yAdj - 399) / 400
    val yoe = yAdj - era * 400                                   // [0, 399]
    val doy = (153L * (if (mo > 2) mo - 3 else mo + 9) + 2) / 5 + d - 1   // [0, 365]
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy              // [0, 146096]
    return era * 146097 + doe - 719468
}

/** Inverse of [epochDay] — the ISO `yyyy-MM-dd` for a day number (Hinnant civil_from_days). */
fun isoFromEpochDay(z: Long): String {
    val zz = z + 719468
    val era = (if (zz >= 0) zz else zz - 146096) / 146097
    val doe = zz - era * 146097                                  // [0, 146096]
    val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365   // [0, 399]
    val y = yoe + era * 400
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)            // [0, 365]
    val mp = (5 * doy + 2) / 153                                 // [0, 11]
    val d = doy - (153 * mp + 2) / 5 + 1                         // [1, 31]
    val mo = if (mp < 10) mp + 3 else mp - 9                     // [1, 12]
    val yy = if (mo <= 2) y + 1 else y
    return iso(yy.toInt(), mo.toInt(), d.toInt())
}

/** ISO date [n] days after [iso] (proleptic Gregorian), or null if [iso] is malformed. */
fun addDays(iso: String, n: Int): String? = epochDay(iso)?.let { isoFromEpochDay(it + n) }

/** ISO date [n] months after [iso], clamping the day to the target month's length (e.g. Jan 31 +1mo → Feb 28/29). */
fun addMonths(iso: String, n: Int): String? {
    val (y, m, d) = dateParts(iso) ?: return null
    val total = y * 12 + (m - 1) + n
    val ny = total.floorDiv(12)
    val nm = total.mod(12) + 1
    return iso(ny, nm, minOf(d, daysInMonth(ny, nm)))
}

/** ISO date [n] years after [iso] (= +[n]·12 months, so Feb-29 clamps in a non-leap year). */
fun addYears(iso: String, n: Int): String? = addMonths(iso, n * 12)

/**
 * Whole elapsed months from [a] to [b] (signed, truncated toward zero) — the kernel's `DifferenceInMonths`.
 * The start day is clamped to the end month's length before the day comparison, so a month-end step counts as
 * a full month (verified: `2020-01-31`→`2020-02-29` = 1, but `2020-01-15`→`2020-02-14` = 0). Null if malformed.
 */
fun differenceInMonths(a: String, b: String): Int? {
    val (ay, am, ad) = dateParts(a) ?: return null
    val (by, bm, bd) = dateParts(b) ?: return null
    var months = (by * 12 + bm) - (ay * 12 + am)
    val days = bd - minOf(ad, daysInMonth(by, bm))
    if (months > 0 && days < 0) months-- else if (months < 0 && days > 0) months++
    return months
}

/** Whole elapsed years from [a] to [b] — the kernel's `DifferenceInYears` (= whole months / 12, toward zero). */
fun differenceInYears(a: String, b: String): Int? = differenceInMonths(a, b)?.div(12)

private fun daysInMonth(year: Int, month: Int): Int = when (month) {
    1, 3, 5, 7, 8, 10, 12 -> 31
    4, 6, 9, 11 -> 30
    else -> if (year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)) 29 else 28
}

private fun iso(y: Int, m: Int, d: Int): String =
    y.toString().padStart(4, '0') + "-" + m.toString().padStart(2, '0') + "-" + d.toString().padStart(2, '0')
