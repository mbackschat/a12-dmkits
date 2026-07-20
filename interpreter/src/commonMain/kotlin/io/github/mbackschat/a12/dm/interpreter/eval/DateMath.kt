package io.github.mbackschat.a12.dm.interpreter.eval

import io.github.mbackschat.a12.dm.interpreter.model.ModelTimeZone

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
 * Whether (year, month, day) is strictly before the kernel's always-on date floor — `Constants.DATE_FROM =
 * 16.10.1583`, "the begin of the gregorian calendar". A real calendar date earlier than that is the kernel's
 * `datumFalsch` (FEHLER_DATUM) formal error, flagged for EVERY stored or computed date value (and a date
 * range's start), independent of the opt-in "younger than 1900" additional check (`DATE_FROM_FOR_ADDITIONAL_CHECK`).
 * Kernel-confirmed in BOTH strategies (groovy-dynamic runtime `FormatDefinitionDatum.dateInvalid` + java-static
 * codegen): the boundary is exact — 1583-10-15 invalid, 1583-10-16 valid (strict `Date.before`).
 */
fun beforeGregorianStart(year: Int, month: Int, day: Int): Boolean =
    year < 1583 || (year == 1583 && (month < 10 || (month == 10 && day < 16)))

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
 * Complete a **date FRAGMENT** value (rendered in its `getFormatOfFragment` [format], one of `yyyy-MM`,
 * `yyyy`, `MM`, `MM-dd`) to its **earliest** concrete ISO `yyyy-MM-dd` date — the kernel's `feldWertAlsDatum`
 * INTERVAL_START completion: a missing MONTH → `01`, a missing DAY → `01`, a missing YEAR → [baseYear] (the
 * model's declared base year, else the kernel default 2000). Returns null when the value does not match its
 * format, has an out-of-range month, or is an impossible calendar day (the kernel's `datumFormatFalsch`);
 * a missing day-in-month uses [baseYear]'s leap-ness (so `MM-dd` = `02-29` is real iff [baseYear] is a leap
 * year — matching the kernel completing with the base year before the realness check).
 *
 * Both kernel strategies read a fragment field through this single completion, so canonicalizing a fragment
 * to this ISO date makes ALL the ordinary date machinery (comparison, `Add*`, extraction) match both — the
 * interpreter never re-enforces the authoring-time comparability/granularity gates (KERNEL-FINDINGS §6).
 */
fun completeFragmentToIso(raw: String, format: String, baseYear: Int): String? {
    val (frag, comps) = dateFragment(format) ?: return null
    val m = Regex("^$frag$").matchEntire(raw) ?: return null
    val g = m.groupValues.drop(1)
    var y = baseYear; var mo = 1; var d = 1
    comps.forEachIndexed { i, c -> val v = g[i].toInt(); when (c) { 'y' -> y = v; 'M' -> mo = v; 'd' -> d = v } }
    if (mo < 1 || mo > 12 || d < 1 || d > daysInMonth(y, mo)) return null
    return iso(y, mo, d)
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
 *
 * **Per-side FRAGMENT completion is ASYMMETRIC (IF118, KERNEL-FINDINGS §6).** When [format] is a fragment —
 * carrying only some of y/M/d (a `yyyy` year-range like `2024/2025`, a real workspace idiom) — the two
 * endpoints are completed to concrete dates by OPPOSITE interval rules, exactly as the kernel's
 * `RuntimeController.datesAndFormatsToDateRange` splits `DateInterval.INTERVAL_START` / `INTERVAL_END` across
 * `DateAndFormat.addMonthAndDay`: the START endpoint fills a missing month → `01` and a missing day → `01`
 * (the earliest date), the END endpoint fills a missing month → `12` and a missing day → the completed
 * month's LAST day (the latest date); a missing year is [baseYear] on both. So `2024/2025` (format `yyyy`) →
 * `2024-01-01 .. 2025-12-31` — the widest interval the endpoints denote. A full-format range (`dd.MM.yyyy`,
 * every component present) completes to itself on both sides, so this is a no-op there.
 */
fun parseRangeToIso(raw: String, format: String, sep: String, baseYear: Int = 2000): Pair<String, String>? {
    val (frag, comps) = dateFragment(format) ?: return null
    val m = Regex("^$frag${Regex.escape(sep)}$frag$").matchEntire(raw) ?: return null
    val g = m.groupValues.drop(1)
    val start = buildRangeEndpoint(g.take(comps.size), comps, baseYear, end = false) ?: return null
    val end = buildRangeEndpoint(g.drop(comps.size), comps, baseYear, end = true) ?: return null
    return start to end
}

/**
 * Assemble the ISO `yyyy-MM-dd` for ONE endpoint of a date range from its fragment's captured [values] in
 * [comps] order, completing the components the [comps] don't carry per the kernel's per-side interval rule
 * (see [parseRangeToIso]): a missing YEAR → [baseYear]; a missing MONTH → `01` (start) or `12` (end); a
 * missing DAY → `01` (start) or the completed month's last day (end). A PRESENT-but-out-of-range component
 * → null (the caller's [resolveValue] gates a formally-invalid range to Unknown before this runs, so this is
 * defensive). The END day is filled AFTER the month, so a missing-month end lands on Dec 31 and a `yyyy-MM`
 * end on that month's real last day (leap-aware).
 */
private fun buildRangeEndpoint(values: List<String>, comps: List<Char>, baseYear: Int, end: Boolean): String? {
    var y = baseYear; var mo = -1; var d = -1
    comps.forEachIndexed { i, c -> val v = values[i].toInt(); when (c) { 'y' -> y = v; 'M' -> mo = v; 'd' -> d = v } }
    if (mo == -1) mo = if (end) 12 else 1                          // missing month → interval bound
    if (mo < 1 || mo > 12) return null                            // a PRESENT month must be real
    if (d == -1) d = if (end) daysInMonth(y, mo) else 1           // missing day → interval bound (leap-aware)
    if (d < 1 || d > daysInMonth(y, mo)) return null              // a PRESENT day must be real
    return iso(y, mo, d)
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
    if (y < 0 || mo < 1 || mo > 12 || d < 1 || d > daysInMonth(y, mo) || h > 23 || mi > 59 || s > 59) return null
    return iso(y, mo, d) + "T" + two(h) + ":" + two(mi) + ":" + two(s)
}

private fun two(n: Int): String = n.toString().padStart(2, '0')

/** Assemble ISO `yyyy-MM-dd` from a date fragment's captured component [values] in [comps] order; null if out of range. */
private fun buildIso(values: List<String>, comps: List<Char>): String? {
    var y = -1; var mo = -1; var d = -1
    comps.forEachIndexed { idx, c -> when (c) { 'y' -> y = values[idx].toInt(); 'M' -> mo = values[idx].toInt(); 'd' -> d = values[idx].toInt() } }
    if (y < 0 || mo < 1 || mo > 12 || d < 1 || d > daysInMonth(y, mo)) return null
    return iso(y, mo, d)
}

/** Whether [s] is a canonical ISO date-time (`yyyy-MM-dd'T'HH:mm:ss`) — the shape every resolved DATE_TIME
 *  value carries; the datetime-comparison dispatch keys on it. */
fun isIsoDateTime(s: String): Boolean = ISO_DATE_TIME.matches(s)

// ---- model-timezone (DST) instant math — the Europe/Berlin axis (IF155 → KERNEL-FINDINGS §6) -------------
//
// The kernel parses every DATE/DATE_TIME with a zone-set formatter, so under a `Europe/Berlin` model wall
// clock and instants diverge across the DST transitions. The interpreter keeps its canonical LOCAL ISO form
// and converts to instants exactly where the kernel's semantics are instant-based. Clean-room encoding of
// Berlin's COMPLETE history as the kernel actually sees it — the legacy `java.util.TimeZone` path
// (`SimpleDateFormat` in `ValidationDateParser`), NOT `java.time`. That distinction is pinned in the table
// below: `java.util` DROPS the pre-1916 LMT (Berlin is flat CET +1 h before the first DST transition), and
// Berlin has THREE historical offsets — CET (+1 h), CEST (+2 h), and CEMT (+3 h, the 1945/47 double-summer
// windows). The [BERLIN_TX_EPOCH]/[BERLIN_TX_OFFSET] transitions (1916→1997) are the kernel's own step
// function (pinned to the JDK 21.0.11 / tzdb 2026a profile and exhaustively differential-locked);
// beyond them the recurring EU rule governs (CEST from the last Sunday of March 01:00Z to the last Sunday
// of October 01:00Z). UTC is the identity on every function here.

/** Epoch day of civil `year-month-day` — Hinnant days_from_civil as PURE integer arithmetic (no string build
 *  or regex, unlike [epochDay]); the DST offset path calls this per datetime value, so it must not allocate. */
internal fun epochDayOfCivil(year: Int, month: Int, day: Int): Long {
    val yAdj = if (month <= 2) year - 1L else year.toLong()
    val era = (if (yAdj >= 0) yAdj else yAdj - 399) / 400
    val yoe = yAdj - era * 400
    val doy = (153L * (if (month > 2) month - 3 else month + 9) + 2) / 5 + day - 1
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
    return era * 146097 + doe - 719468
}

/** The civil YEAR of an epoch [day] — Hinnant civil_from_days as PURE integer arithmetic (no string build,
 *  unlike `isoFromEpochDay(...).substring(0,4).toInt()`); the DST offset path calls this per datetime value. */
internal fun yearOfEpochDay(day: Long): Int {
    val zz = day + 719468
    val era = (if (zz >= 0) zz else zz - 146096) / 146097
    val doe = zz - era * 146097
    val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
    val y = yoe + era * 400
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val mo = if (mp < 10) mp + 3 else mp - 9
    return (if (mo <= 2) y + 1 else y).toInt()
}

/** Epoch day of the last Sunday of [month] (March or October — both 31 days) in [year].
 *  1970-01-01 (epoch day 0) was a Thursday, so a day is a Sunday iff `(epochDay + 4) % 7 == 0`. */
private fun lastSundayEpochDay(year: Int, month: Int): Long {
    val eom = epochDayOfCivil(year, month, 31)
    return eom - (eom + 4).mod(7L)
}

/** The instant Berlin DST starts in [year] — last Sunday of March, 01:00:00Z (02:00 CET → 03:00 CEST). */
private fun berlinDstStart(year: Int): Long = lastSundayEpochDay(year, 3) * 86_400L + 3_600L

/** The instant Berlin DST ends in [year] — last Sunday of October, 01:00:00Z (03:00 CEST → 02:00 CET). */
private fun berlinDstEnd(year: Int): Long = lastSundayEpochDay(year, 10) * 86_400L + 3_600L

/** The distinct UTC offsets (seconds) Berlin has ever used — CET, CEST, and the 1945/47 CEMT (+3 h) — ASCENDING.
 *  A wall time has at most one instant per offset; the wall→instant resolution tries them in this order. */
private val BERLIN_OFFSETS = longArrayOf(3_600L, 7_200L, 10_800L)

// The kernel's OWN transition step function (`java.util.TimeZone.getOffset` for Europe/Berlin), 1916→1997 —
// pinned to JDK 21 tzdb 2026a. Each epoch-second in [BERLIN_TX_EPOCH] is when the offset in the aligned slot
// of [BERLIN_TX_OFFSET] takes effect; before the first entry Berlin is flat CET (java.util drops the LMT),
// and at/after the last entry the recurring EU rule governs. Ascending, so [berlinOffsetSeconds] binary-searches.
private val BERLIN_TX_EPOCH = longArrayOf(
         -1693706400L, -1680483600L, -1663455600L, -1650150000L, -1632006000L, -1618700400L,
          -938905200L,  -857257200L,  -844556400L,  -828226800L,  -812502000L,  -796777200L,
          -781052400L,  -776563200L,  -765936000L,  -761180400L,  -748479600L,  -733273200L,
          -717631200L,  -714610800L,  -710380800L,  -701910000L,  -684975600L,  -670460400L,
          -654130800L,  -639010800L,   323830800L,   338950800L,   354675600L,   370400400L,
           386125200L,   401850000L,   417574800L,   433299600L,   449024400L,   465354000L,
           481078800L,   496803600L,   512528400L,   528253200L,   543978000L,   559702800L,
           575427600L,   591152400L,   606877200L,   622602000L,   638326800L,   654656400L,
           670381200L,   686106000L,   701830800L,   717555600L,   733280400L,   749005200L,
           764730000L,   780454800L,   796179600L,   811904400L,   828234000L,   846378000L,
           859683600L,   877827600L,
    )
private val BERLIN_TX_OFFSET = longArrayOf(
          7200L,  3600L,  7200L,  3600L,  7200L,  3600L,
          7200L,  3600L,  7200L,  3600L,  7200L,  3600L,
          7200L, 10800L,  7200L,  3600L,  7200L,  3600L,
          7200L, 10800L,  7200L,  3600L,  7200L,  3600L,
          7200L,  3600L,  7200L,  3600L,  7200L,  3600L,
          7200L,  3600L,  7200L,  3600L,  7200L,  3600L,
          7200L,  3600L,  7200L,  3600L,  7200L,  3600L,
          7200L,  3600L,  7200L,  3600L,  7200L,  3600L,
          7200L,  3600L,  7200L,  3600L,  7200L,  3600L,
          7200L,  3600L,  7200L,  3600L,  7200L,  3600L,
          7200L,  3600L,
    )

/** Test-visible pinned-profile accessors; evaluation still reads the private arrays directly. */
internal val berlinPinnedTransitionCount: Int get() = BERLIN_TX_EPOCH.size
internal fun berlinPinnedTransitionEpoch(index: Int): Long = BERLIN_TX_EPOCH[index]
internal fun berlinPinnedTransitionOffset(index: Int): Long = BERLIN_TX_OFFSET[index]

/** Berlin's UTC offset in seconds at [instant] over its COMPLETE history — flat CET before 1916 (the kernel's
 *  java.util path drops the LMT), the pinned [BERLIN_TX_EPOCH]/[BERLIN_TX_OFFSET] transitions through 1997
 *  (CET/CEST/CEMT), and the recurring EU rule beyond. Binary search + pure-integer civil math ([berlinEuRuleOffset])
 *  — no per-call string allocation — because this is on the hot path for every conversion under a Berlin model. */
private fun berlinOffsetSeconds(instant: Long): Long {
    if (instant < BERLIN_TX_EPOCH[0]) return 3_600L                 // pre-1916: flat CET (no LMT on the kernel path)
    val last = BERLIN_TX_EPOCH.size - 1
    if (instant >= BERLIN_TX_EPOCH[last]) return berlinEuRuleOffset(instant)   // 1997-10-26 onward: the stable EU rule
    var lo = 0; var hi = last                                       // greatest i with BERLIN_TX_EPOCH[i] <= instant
    while (lo < hi) {
        val mid = (lo + hi + 1) ushr 1
        if (BERLIN_TX_EPOCH[mid] <= instant) lo = mid else hi = mid - 1
    }
    return BERLIN_TX_OFFSET[lo]
}

/** Berlin's offset under the recurring EU rule (in force continuously since 1996): CEST (+2 h) from the last
 *  Sunday of March 01:00Z to the last Sunday of October 01:00Z, else CET (+1 h). Governs the future beyond the
 *  pinned table; the table itself carries the irregular 1916→1995 history the rule does not describe. */
private fun berlinEuRuleOffset(instant: Long): Long {
    val year = yearOfEpochDay(instant.floorDiv(86_400L))
    return if (instant >= berlinDstStart(year) && instant < berlinDstEnd(year)) 7_200L else 3_600L
}

/**
 * The instant of a LOCAL wall time (naive epoch seconds, [epochSecond]) under [tz] — the kernel's PARSE
 * resolution (a zone-set, non-lenient `SimpleDateFormat`, probe-verified over all of Berlin's history):
 *  - a wall time with exactly one consistent offset resolves to it (winter CET, summer CEST, midsummer CEMT);
 *  - the **ambiguous** fall-back hour (two consistent offsets) resolves to the **smaller (after) offset** —
 *    standard-time in a normal fall-back, CEST in the 1945 CEMT→CEST overlap;
 *  - the **nonexistent** spring-forward gap resolves to **null** — the kernel REJECTS it (`datumFormatFalsch`).
 *
 * The rule is one loop: try the candidate offsets ASCENDING and take the first instant whose true offset
 * ([berlinOffsetSeconds]) equals the offset assumed — so an overlap picks the smaller offset (the kernel's
 * after/standard choice) and a gap matches none.
 */
fun localToInstant(localSeconds: Long, tz: ModelTimeZone): Long? = when (tz) {
    ModelTimeZone.UTC -> localSeconds
    ModelTimeZone.EUROPE_BERLIN -> {
        var resolved: Long? = null
        for (offset in BERLIN_OFFSETS) {
            val instant = localSeconds - offset
            if (berlinOffsetSeconds(instant) == offset) { resolved = instant; break }
        }
        resolved                                           // null ⇒ the spring gap — no such wall time
    }
}

/** The LOCAL wall time (naive epoch seconds) of [instant] under [tz]. */
fun instantToLocal(instant: Long, tz: ModelTimeZone): Long = when (tz) {
    ModelTimeZone.UTC -> instant
    ModelTimeZone.EUROPE_BERLIN -> instant + berlinOffsetSeconds(instant)
}

/** The instant of a canonical ISO date-time under [tz], or null when malformed OR a gap wall time. */
fun zonedInstantOf(iso: String, tz: ModelTimeZone): Long? = epochSecond(iso)?.let { localToInstant(it, tz) }

/** The canonical ISO date-time rendering of [instant]'s wall clock under [tz]. */
fun isoFromZonedInstant(instant: Long, tz: ModelTimeZone): String = isoFromEpochSecond(instantToLocal(instant, tz))

/** Whether a well-formed local date-time falls in [tz]'s spring-forward gap (a wall time that does not
 *  exist) — the kernel's `datumFormatFalsch` for a stored value (probe-verified, KERNEL-FINDINGS §6). */
fun isGapLocal(iso: String, tz: ModelTimeZone): Boolean =
    tz != ModelTimeZone.UTC && epochSecond(iso)?.let { localToInstant(it, tz) == null } == true

/**
 * The landing instant of a WALL-day add over a date-time: nominal wall = [localSeconds] + [n]·86 400 (the
 * kernel's zone-calendar `add(DAY_OF_MONTH)` keeps the clock time). A nominal landing with one consistent
 * offset resolves to it; a landing in a **special hour** resolves so the step never OVERSHOOTS the direction
 * of travel (kernel-probed on all four combinations, KERNEL-FINDINGS §6): a **forward** add takes the
 * DAYLIGHT (earlier) instant — the gap landing renders `01:30` CET, the ambiguous landing keeps CEST — and a
 * **backward** add takes the STANDARD (later) instant.
 */
fun wallDayLandingInstant(localSeconds: Long, n: Int, tz: ModelTimeZone): Long {
    val nominal = localSeconds + n * 86_400L
    if (tz == ModelTimeZone.UTC) return nominal

    var earliestConsistent = Long.MAX_VALUE
    var latestConsistent = Long.MIN_VALUE
    var gapAfterOffset = Long.MIN_VALUE
    var gapBeforeOffset = Long.MIN_VALUE
    for (offset in BERLIN_OFFSETS) {
        val instant = nominal - offset
        val actualOffset = berlinOffsetSeconds(instant)
        if (actualOffset == offset) {
            earliestConsistent = minOf(earliestConsistent, instant)
            latestConsistent = maxOf(latestConsistent, instant)
        } else if (actualOffset < offset && gapAfterOffset == Long.MIN_VALUE) {
            gapAfterOffset = offset
        } else if (actualOffset > offset) {
            gapBeforeOffset = offset
        }
    }
    if (earliestConsistent != Long.MAX_VALUE) {
        // No overshoot: a FORWARD add takes the DAYLIGHT (earliest) instant, a BACKWARD add the STANDARD (latest).
        return if (n > 0) earliestConsistent else latestConsistent
    }
    // The gap: no consistent instant. The bracketing transition is oBefore→oAfter (oAfter the larger offset);
    // forward straddles to the daylight (earlier) instant `nominal − oAfter`, backward to the standard `nominal − oBefore`.
    check(gapAfterOffset != Long.MIN_VALUE && gapBeforeOffset != Long.MIN_VALUE) {
        "Berlin wall-day landing has no consistent or bracketing offset for local=$nominal"
    }
    return if (n > 0) nominal - gapAfterOffset else nominal - gapBeforeOffset
}
