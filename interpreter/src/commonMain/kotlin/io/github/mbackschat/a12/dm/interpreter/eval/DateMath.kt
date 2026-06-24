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

private val ISO_DATE_TIME = Regex("""(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})""")

/** Seconds since 1970-01-01T00:00:00 for a canonical ISO `yyyy-MM-dd'T'HH:mm:ss`, or null if malformed. The
 *  date-time math (`Add{Hours,Minutes,Seconds}`, `DifferenceIn{Hours,Minutes,Seconds}`) reduces to integer
 *  arithmetic on this, so a day boundary carries automatically. */
fun epochSecond(iso: String): Long? {
    val m = ISO_DATE_TIME.matchEntire(iso) ?: return null
    val day = epochDay("${m.groupValues[1]}-${m.groupValues[2]}-${m.groupValues[3]}") ?: return null
    val h = m.groupValues[4].toInt(); val mi = m.groupValues[5].toInt(); val s = m.groupValues[6].toInt()
    if (h > 23 || mi > 59 || s > 59) return null
    return day * 86_400L + h * 3_600L + mi * 60L + s
}

/** Inverse of [epochSecond] — the canonical ISO date-time for a second count (handles day rollover via floorDiv). */
fun isoFromEpochSecond(z: Long): String {
    val tod = z.mod(86_400L)                                 // [0, 86399], floor-mod so a negative z still rolls back
    val date = isoFromEpochDay(z.floorDiv(86_400L))
    return date + "T" + two((tod / 3_600).toInt()) + ":" + two(((tod % 3_600) / 60).toInt()) + ":" + two((tod % 60).toInt())
}

/** ISO date-time [seconds] after [iso] (carrying across days), or null if [iso] is malformed. */
fun addDateTimeSeconds(iso: String, seconds: Long): String? = epochSecond(iso)?.let { isoFromEpochSecond(it + seconds) }

/** ISO date [n] months after [iso], clamping the day to the target month's length (e.g. Jan 31 +1mo → Feb 28/29). */
fun addMonths(iso: String, n: Int): String? {
    val (y, m, d) = dateParts(iso) ?: return null
    val total = y * 12 + (m - 1) + n
    val ny = total.floorDiv(12)
    val nm = total.mod(12) + 1
    return iso(ny, nm, minOf(d, daysInMonth(ny, nm)))
}

/**
 * ISO date [n] years after [iso]. End-of-February is preserved: the LAST day of February maps to the
 * target year's last day of February (Feb 28 of a non-leap year → Feb 29 of a leap year, and vice versa) —
 * matching the kernel's runtime behavior (NOT the same as `+12·n` months, which only clamps downward). Any
 * other day clamps to the target month's length (a no-op outside February, since only February's length
 * varies by year).
 */
fun addYears(iso: String, n: Int): String? {
    val (y, m, d) = dateParts(iso) ?: return null
    val ty = y + n
    val day = if (m == 2 && d == daysInMonth(y, 2)) daysInMonth(ty, 2) else minOf(d, daysInMonth(ty, m))
    return iso(ty, m, day)
}

/**
 * Whole elapsed months from [a] to [b] — signed (negative when [b] precedes [a]), the count of complete
 * months between them. Defined behaviorally as the largest `n` such that [addMonths] of the earlier date by
 * `n` does not pass the later date — so a month-end step counts as full (`2020-01-31`→`2020-02-29` = 1) and a
 * partial month does not (`2020-01-15`→`2020-02-14` = 0). Matches the kernel's runtime; null if malformed.
 */
fun differenceInMonths(a: String, b: String): Int? = wholePeriods(a, b, ::addMonths)

/** Whole elapsed years from [a] to [b] — signed; the [addYears] (end-of-February-preserving) analogue of [differenceInMonths]. */
fun differenceInYears(a: String, b: String): Int? = wholePeriods(a, b, ::addYears)

/** Signed count of complete periods between [a] and [b]: the largest n with [add](earlier, n) not past the later date. */
private fun wholePeriods(a: String, b: String, add: (String, Int) -> String?): Int? {
    val ea = epochDay(a) ?: return null
    val eb = epochDay(b) ?: return null
    val lo = if (ea <= eb) a else b
    val hi = maxOf(ea, eb)
    var n = 0
    while ((epochDay(add(lo, n + 1) ?: return null) ?: return null) <= hi) n++
    return if (ea <= eb) n else -n
}

/** Whether (year, month, day) is a real calendar date — month 1..12 and day within that month's length
 *  (leap-aware). The realness test behind the `Date(...)` constructor and the `Valid`/`Invalid` predicates. */
fun isRealDate(year: Int, month: Int, day: Int): Boolean = month in 1..12 && day in 1..daysInMonth(year, month)

/**
 * Parse a **DAY_OPTIONAL** date value rendered in [format] — like [parseDateToIso] but the DAY may be the
 * sentinel `00` (day omitted). Returns `(year, month, day)` with `day == 0` for an omitted day, or null if
 * malformed (bad month, an out-of-range real day, or the format has no day component). The basis for the
 * `DAY_OPTIONAL` formal check and `ValueAsDate`.
 */
fun dayOptionalParts(raw: String, format: String): Triple<Int, Int, Int>? {
    val (frag, comps) = dateFragment(format) ?: return null
    val m = Regex("^$frag$").matchEntire(raw) ?: return null
    val g = m.groupValues.drop(1)
    var y = -1; var mo = -1; var d = -1
    comps.forEachIndexed { i, c -> val v = g[i].toInt(); when (c) { 'y' -> y = v; 'M' -> mo = v; 'd' -> d = v } }
    if (y < 0 || mo < 1 || mo > 12 || d < 0) return null            // year/month required; format must carry a day
    if (d != 0 && d > daysInMonth(y, mo)) return null               // a specified day must be real
    return Triple(y, mo, d)
}

/**
 * Resolve a DAY_OPTIONAL date value to a concrete ISO date: a specified day → that exact date; an omitted
 * day (`00`) → day 01 ([lastDay] = false) or the month's last day ([lastDay] = true). Null if malformed. The
 * `ValueAsDate(field, FirstDay\|LastDay)` resolution.
 */
fun resolvePartialDate(raw: String, format: String, lastDay: Boolean): String? {
    val (y, mo, d) = dayOptionalParts(raw, format) ?: return null
    val day = if (d != 0) d else if (lastDay) daysInMonth(y, mo) else 1
    return iso(y, mo, day)
}

/** The canonical ISO `yyyy-MM-dd` for (year, month, day) — zero-padded; the `Date(...)` constructor's value. */
fun isoDate(year: Int, month: Int, day: Int): String = iso(year, month, day)

private fun daysInMonth(year: Int, month: Int): Int = when (month) {
    1, 3, 5, 7, 8, 10, 12 -> 31
    4, 6, 9, 11 -> 30
    else -> if (year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)) 29 else 28
}

private fun iso(y: Int, m: Int, d: Int): String =
    y.toString().padStart(4, '0') + "-" + m.toString().padStart(2, '0') + "-" + d.toString().padStart(2, '0')

// ---- format-aware parsing (non-ISO date fields + date ranges) ------------------------------------------
//
// The interpreter canonicalizes every date to ISO `yyyy-MM-dd` so the extractors/arithmetic/comparison above
// stay ISO-based. A field declares its rendering format (the kernel's `DateType.format` etc.); these helpers
// turn a format-rendered value back into ISO. The format is parsed structurally (runs of y/M/d as digit
// groups, other chars literal), so it generalizes beyond the fixtures' `dd.MM.yyyy` without a per-format table.

/**
 * Parse a date-RANGE value `<date><sep><date>` — each endpoint rendered in [format], joined by [sep] with no
 * padding (the kernel's canonical, e.g. `01.06.2021-30.06.2021`) — into a pair of ISO `yyyy-MM-dd` dates, or
 * null when it doesn't match (a malformed range, the kernel's `datumBereichFormatFalsch`). The two halves are
 * matched as one anchored regex, so a [sep] that also appears inside [format] stays unambiguous.
 */
fun parseRangeToIso(raw: String, format: String, sep: String): Pair<String, String>? {
    val (frag, comps) = dateFragment(format) ?: return null
    val m = Regex("^$frag${Regex.escape(sep)}$frag$").matchEntire(raw) ?: return null
    val g = m.groupValues.drop(1)
    val start = buildIso(g.take(comps.size), comps) ?: return null
    val end = buildIso(g.drop(comps.size), comps) ?: return null
    return start to end
}

/**
 * Parse a single date value rendered in [format] (the kernel's `DateType.format`, e.g. `dd.MM.yyyy`,
 * `MM/dd/yyyy`, or ISO `yyyy-MM-dd`) into the canonical ISO `yyyy-MM-dd`, or null when it doesn't match the
 * format (a malformed date — the kernel's `datumFormatFalsch`). The interpreter stores every date as ISO so
 * comparison stays lexicographic-chronological and the extractors parse directly; this is how a non-ISO
 * field's rendered value is brought to that canonical form (locale-variant by reading the declared format).
 */
fun parseDateToIso(raw: String, format: String): String? {
    val (frag, comps) = dateFragment(format) ?: return null
    val m = Regex("^$frag$").matchEntire(raw) ?: return null
    return buildIso(m.groupValues.drop(1), comps)
}

/**
 * Render an ISO `yyyy-MM-dd` date back into [format] (the kernel's `DateType`/`DateRangeType.format`, e.g.
 * `dd.MM.yyyy`) — the inverse of [parseDateToIso], used to render a computed `DateRange(...)` to its target
 * field's format. Each `y`/`M`/`d` run is the component zero-padded to the run length; other characters are
 * literal. Null if [iso] is malformed.
 */
fun renderDateToFormat(iso: String, format: String): String? {
    val (y, mo, d) = dateParts(iso) ?: return null
    val sb = StringBuilder()
    var i = 0
    while (i < format.length) {
        val c = format[i]
        if (c == 'y' || c == 'M' || c == 'd') {
            var j = i
            while (j < format.length && format[j] == c) j++
            val v = when (c) { 'y' -> y; 'M' -> mo; else -> d }
            sb.append(v.toString().padStart(j - i, '0'))
            i = j
        } else {
            sb.append(c); i++
        }
    }
    return sb.toString()
}

/** A regex fragment with one capture group per `y`/`M`/`d` run (exact width = run length) plus the component
 *  order; literals are escaped. Null if [format] carries no date component. */
private fun dateFragment(format: String): Pair<String, List<Char>>? {
    val sb = StringBuilder()
    val comps = mutableListOf<Char>()
    var i = 0
    while (i < format.length) {
        val c = format[i]
        if (c == 'y' || c == 'M' || c == 'd') {
            var j = i
            while (j < format.length && format[j] == c) j++
            sb.append("(\\d{").append(j - i).append("})")
            comps.add(c)
            i = j
        } else {
            sb.append(Regex.escape(c.toString()))
            i++
        }
    }
    return if (comps.isEmpty()) null else sb.toString() to comps
}

/**
 * Parse a date-TIME value rendered in [format] (the kernel's `DateTimeType.format`, e.g.
 * `yyyy-MM-dd'T'HH:mm:ss`) into the canonical ISO `yyyy-MM-dd'T'HH:mm:ss`, or null when it doesn't match
 * (a malformed date-time). Like [parseDateToIso] the interpreter stores every date-time in this fixed
 * canonical form so a comparison stays lexicographic-chronological (`2000-…T00:00:00` < `2999-…T23:59:59`)
 * and the extractors can read it; this brings a locale-variant rendered value to that form by reading the
 * declared format. The time components default to 0 when the format omits them (a date-only datetime).
 */
fun parseDateTimeToIso(raw: String, format: String): String? {
    val (frag, comps) = dateTimeFragment(format) ?: return null
    val m = Regex("^$frag$").matchEntire(raw) ?: return null
    return buildIsoDateTime(m.groupValues.drop(1), comps)
}

/**
 * Validate a TIME value against its [format] (e.g. `HH:mm:ss`) and normalize to `HH:mm:ss`, or null if
 * malformed — the time-only twin of [parseDateTimeToIso] (no date component). A format carrying any date run
 * (`y`/`M`/`d`) is not a time format → null. Out-of-range components (`H>23`, `m`/`s`>59) → null. Used by the
 * formal check (a malformed TIME resolves to `Val.Unknown` and emits the kernel's `datumFormatFalsch`).
 */
fun parseTimeToIso(raw: String, format: String): String? {
    val (frag, comps) = dateTimeFragment(format) ?: return null
    if (comps.any { it == 'y' || it == 'M' || it == 'd' }) return null
    val m = Regex("^$frag$").matchEntire(raw) ?: return null
    var h = 0; var mi = 0; var s = 0
    comps.forEachIndexed { i, c -> val v = m.groupValues[i + 1].toInt(); when (c) { 'H' -> h = v; 'm' -> mi = v; 's' -> s = v } }
    if (h > 23 || mi > 59 || s > 59) return null
    return two(h) + ":" + two(mi) + ":" + two(s)
}

/**
 * Like [dateFragment] but also recognizes the time runs `H`/`m`/`s`, and treats a single-quoted section
 * `'…'` as a SimpleDateFormat-style literal (the quotes are delimiters, `''` is a literal quote) — so a
 * `yyyy-MM-dd'T'HH:mm:ss` format matches a rendered `2024-06-01T12:30:00` (no quotes in the value). Null
 * if [format] carries no date/time component.
 */
private fun dateTimeFragment(format: String): Pair<String, List<Char>>? {
    val sb = StringBuilder()
    val comps = mutableListOf<Char>()
    var i = 0
    while (i < format.length) {
        val c = format[i]
        when {
            c == 'y' || c == 'M' || c == 'd' || c == 'H' || c == 'm' || c == 's' -> {
                var j = i
                while (j < format.length && format[j] == c) j++
                sb.append("(\\d{").append(j - i).append("})")
                comps.add(c)
                i = j
            }
            c == '\'' -> {                                   // a quoted literal section: 'T' → literal T, '' → '
                i++
                while (i < format.length && format[i] != '\'') { sb.append(Regex.escape(format[i].toString())); i++ }
                if (i < format.length) i++                   // consume the closing quote
            }
            else -> { sb.append(Regex.escape(c.toString())); i++ }
        }
    }
    return if (comps.isEmpty()) null else sb.toString() to comps
}

/** Assemble ISO `yyyy-MM-dd'T'HH:mm:ss` from a date-time fragment's captured component [values] in [comps]
 *  order; omitted time components default to 0; null if any component is out of range. */
private fun buildIsoDateTime(values: List<String>, comps: List<Char>): String? {
    var y = -1; var mo = -1; var d = -1; var h = 0; var mi = 0; var s = 0
    comps.forEachIndexed { idx, c ->
        val v = values[idx].toInt()
        when (c) { 'y' -> y = v; 'M' -> mo = v; 'd' -> d = v; 'H' -> h = v; 'm' -> mi = v; 's' -> s = v }
    }
    if (y < 0 || mo < 1 || mo > 12 || d < 1 || d > 31 || h > 23 || mi > 59 || s > 59) return null
    return iso(y, mo, d) + "T" + two(h) + ":" + two(mi) + ":" + two(s)
}

private fun two(n: Int): String = n.toString().padStart(2, '0')

/** Assemble ISO `yyyy-MM-dd` from a date fragment's captured component [values] in [comps] order; null if out of range. */
private fun buildIso(values: List<String>, comps: List<Char>): String? {
    var y = -1; var mo = -1; var d = -1
    comps.forEachIndexed { idx, c -> when (c) { 'y' -> y = values[idx].toInt(); 'M' -> mo = values[idx].toInt(); 'd' -> d = values[idx].toInt() } }
    if (y < 0 || mo < 1 || mo > 12 || d < 1 || d > 31) return null
    return iso(y, mo, d)
}
