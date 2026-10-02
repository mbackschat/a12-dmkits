package io.github.mbackschat.a12.dm.interpreter.eval

import io.github.mbackschat.a12.dm.interpreter.model.DatePrecision
import io.github.mbackschat.a12.dm.interpreter.model.RangeYearInterpretation
import io.github.mbackschat.a12.dm.interpreter.model.ModelTimeZone

/**
 * Pure date arithmetic on ISO `yyyy-MM-dd` strings — no `java.time`/luxon, so it stays in commonMain and
 * runs identically on JVM and JS. [epochDay] is the proleptic-Gregorian day number used by stored values;
 * the constructor-specific legacy-hybrid calendar and model-zone calendar-step operations are explicit
 * sibling seams below rather than implicit host date-library behavior.
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

/** Calendar provenance carried by a date-valued expression. Stored values use [PROLEPTIC_GREGORIAN];
 * `Date(...)` and its date-shift descendants use [LEGACY_HYBRID], matching the kernel's `VkDate` instant. */
internal enum class DateCalendarBasis { PROLEPTIC_GREGORIAN, LEGACY_HYBRID }

/** Absolute local-day number for [iso] under [basis]. */
internal fun calendarEpochDay(iso: String, basis: DateCalendarBasis): Long? = when (basis) {
    DateCalendarBasis.PROLEPTIC_GREGORIAN -> epochDay(iso)
    DateCalendarBasis.LEGACY_HYBRID -> legacyEpochDay(iso)
}

/** Validated date parts under [basis]. */
internal fun calendarDateParts(iso: String, basis: DateCalendarBasis): Triple<Int, Int, Int>? {
    val parts = dateParts(iso) ?: return null
    val real = when (basis) {
        DateCalendarBasis.PROLEPTIC_GREGORIAN -> isRealDate(parts.first, parts.second, parts.third)
        DateCalendarBasis.LEGACY_HYBRID -> legacyCalendarSystem(parts.first, parts.second, parts.third) != null &&
            parts.third <= legacyDaysInMonth(parts.first, parts.second)
    }
    return parts.takeIf { real }
}

/** A legacy-calendar day shift, including the 1582-10-04 → 1582-10-15 cutover step. */
internal fun addLegacyCalendarDays(iso: String, n: Int): String? =
    legacyEpochDay(iso)?.let { isoFromLegacyEpochDay(it + n) }

/** A legacy `Calendar.MONTH` shift. The target day is clamped; a target in the cutover hole normalizes ahead. */
internal fun addLegacyCalendarMonths(iso: String, n: Int): String? {
    val (year, month, day) = calendarDateParts(iso, DateCalendarBasis.LEGACY_HYBRID) ?: return null
    val total = year * 12 + month - 1 + n
    val targetYear = total.floorDiv(12)
    val targetMonth = total.mod(12) + 1
    return normalizedLegacyTarget(targetYear, targetMonth, minOf(day, legacyDaysInMonth(targetYear, targetMonth)))
}

/**
 * A legacy `Calendar.YEAR` shift plus the kernel's explicit February-28 correction. The correction tests
 * leap years with the runtime's Gregorian predicate even on the Julian side; preserving that oddity is part
 * of the observable 30.8.1 contract.
 */
internal fun addLegacyCalendarYears(iso: String, n: Int): String? {
    val (year, month, day) = calendarDateParts(iso, DateCalendarBasis.LEGACY_HYBRID) ?: return null
    val targetYear = year + n
    var result = normalizedLegacyTarget(targetYear, month, minOf(day, legacyDaysInMonth(targetYear, month)))
        ?: return null
    val resultParts = calendarDateParts(result, DateCalendarBasis.LEGACY_HYBRID) ?: return null
    if (month == 2 && day == 28 && resultParts.second == 2 && resultParts.third == 28 &&
        !isGregorianLeapYear(year) && isGregorianLeapYear(targetYear)) {
        result = addLegacyCalendarDays(result, 1) ?: return null
    }
    return result
}

/** Signed complete legacy-calendar months between two date labels. */
internal fun differenceInLegacyCalendarMonths(a: String, b: String): Int? {
    val ordered = orderedLegacyDates(a, b) ?: return null
    val years = positiveLegacyYearDifference(ordered.earlier, ordered.later)
    var months = years * 12
    while (true) {
        val landing = addLegacyCalendarMonths(ordered.earlier, months) ?: return null
        if ((legacyEpochDay(landing) ?: return null) > ordered.laterEpochDay) break
        months++
    }
    val value = months - 1
    return if (ordered.forward) value else -value
}

/** Signed complete legacy-calendar years between two date labels. */
internal fun differenceInLegacyCalendarYears(a: String, b: String): Int? {
    val ordered = orderedLegacyDates(a, b) ?: return null
    val value = positiveLegacyYearDifference(ordered.earlier, ordered.later)
    return if (ordered.forward) value else -value
}

private val ISO_DATE_TIME = Regex("""(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})""")
private val TIME_OF_DAY = Regex("""(\d{2}):(\d{2}):(\d{2})""")

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

/** Whether (year, month, day) is a real date in the proleptic Gregorian calendar. This remains the helper
 *  for stored-value parsing, fragments, ranges, and arithmetic. `Date(...)` construction deliberately uses
 *  [isConstructibleDate] instead: the kernel constructs through a legacy hybrid calendar in the model zone. */
fun isRealDate(year: Int, month: Int, day: Int): Boolean = month in 1..12 && day in 1..daysInMonth(year, month)

/**
 * Whether `Date(day, month, year)` can be constructed by the kernel's non-lenient legacy calendar in [timeZone].
 *
 * The default `GregorianCalendar` is hybrid: Julian through 1582-10-04, the ten civil labels
 * 1582-10-05..14 do not exist, and Gregorian from 1582-10-15. The interpreter's supported UTC/GMT and
 * Europe/Berlin profiles have no whole-local-date discontinuity of their own, so this hybrid calendar is
 * their complete construction-reality rule. Other Java zones (for example Pacific/Apia, which skipped
 * 2011-12-30) remain outside [ModelTimeZone] and are refused before evaluation rather than silently receiving
 * this narrower answer.
 */
internal fun isConstructibleDate(year: Int, month: Int, day: Int, timeZone: ModelTimeZone): Boolean {
    if (legacyCalendarSystem(year, month, day) == null || day > legacyDaysInMonth(year, month)) return false

    // The supported profiles have hour-scale transitions only; neither removes a complete local date.
    // Keeping the exhaustive zone branch here makes that profile restriction explicit and fail-closed when
    // ModelTimeZone grows.
    return when (timeZone) {
        ModelTimeZone.UTC, ModelTimeZone.EUROPE_BERLIN -> true
    }
}

private fun legacyCalendarSystem(year: Int, month: Int, day: Int): CalendarSystem? {
    if (year <= 0 || month !in 1..12 || day <= 0) return null
    return when {
        year < 1582 || (year == 1582 && month < 10) ||
            (year == 1582 && month == 10 && day <= 4) -> CalendarSystem.JULIAN
        year > 1582 || (year == 1582 && month > 10) ||
            (year == 1582 && month == 10 && day >= 15) -> CalendarSystem.GREGORIAN
        else -> null
    }
}

private fun legacyDaysInMonth(year: Int, month: Int): Int = when (month) {
    1, 3, 5, 7, 8, 10, 12 -> 31
    4, 6, 9, 11 -> 30
    else -> if (legacyCalendarSystem(year, month, 1)?.isLeap(year) == true) 29 else 28
}

private enum class CalendarSystem {
    JULIAN {
        override fun isLeap(year: Int): Boolean = year % 4 == 0
    },
    GREGORIAN {
        override fun isLeap(year: Int): Boolean = isGregorianLeapYear(year)
    };

    abstract fun isLeap(year: Int): Boolean
}

private const val UNIX_EPOCH_JULIAN_DAY = 2_440_588L
private val LEGACY_GREGORIAN_CUTOVER_DAY = epochDay("1582-10-15")!!

/** Absolute day number of a non-lenient default `GregorianCalendar` civil label. */
private fun legacyEpochDay(iso: String): Long? {
    val (year, month, day) = dateParts(iso) ?: return null
    val system = legacyCalendarSystem(year, month, day) ?: return null
    if (day > legacyDaysInMonth(year, month)) return null
    val a = (14 - month) / 12
    val y = year.toLong() + 4_800L - a
    val m = month + 12 * a - 3
    val julianDay = when (system) {
        CalendarSystem.GREGORIAN ->
            day + (153L * m + 2L) / 5L + 365L * y + y / 4L - y / 100L + y / 400L - 32_045L
        CalendarSystem.JULIAN ->
            day + (153L * m + 2L) / 5L + 365L * y + y / 4L - 32_083L
    }
    return julianDay - UNIX_EPOCH_JULIAN_DAY
}

/** Civil label of an absolute day under the default hybrid cutover calendar. */
private fun isoFromLegacyEpochDay(epochDay: Long): String {
    val julianDay = epochDay + UNIX_EPOCH_JULIAN_DAY
    return if (epochDay >= LEGACY_GREGORIAN_CUTOVER_DAY) {
        val a = julianDay + 32_044L
        val b = (4L * a + 3L) / 146_097L
        val c = a - 146_097L * b / 4L
        val d = (4L * c + 3L) / 1_461L
        val e = c - 1_461L * d / 4L
        val m = (5L * e + 2L) / 153L
        iso(
            (100L * b + d - 4_800L + m / 10L).toInt(),
            (m + 3L - 12L * (m / 10L)).toInt(),
            (e - (153L * m + 2L) / 5L + 1L).toInt(),
        )
    } else {
        val c = julianDay + 32_082L
        val d = (4L * c + 3L) / 1_461L
        val e = c - 1_461L * d / 4L
        val m = (5L * e + 2L) / 153L
        iso(
            (d - 4_800L + m / 10L).toInt(),
            (m + 3L - 12L * (m / 10L)).toInt(),
            (e - (153L * m + 2L) / 5L + 1L).toInt(),
        )
    }
}

/** `Calendar.add` is lenient: a target field tuple in the cutover hole normalizes ten labels ahead. */
private fun normalizedLegacyTarget(year: Int, month: Int, day: Int): String? {
    if (year <= 0 || month !in 1..12 || day !in 1..legacyDaysInMonth(year, month)) return null
    return if (year == 1582 && month == 10 && day in 5..14) iso(year, month, day + 10)
    else iso(year, month, day)
}

private data class OrderedLegacyDates(
    val earlier: String,
    val later: String,
    val laterEpochDay: Long,
    val forward: Boolean,
)

private fun orderedLegacyDates(a: String, b: String): OrderedLegacyDates? {
    val aDay = legacyEpochDay(a) ?: return null
    val bDay = legacyEpochDay(b) ?: return null
    return if (aDay <= bDay) OrderedLegacyDates(a, b, bDay, true)
    else OrderedLegacyDates(b, a, aDay, false)
}

private fun positiveLegacyYearDifference(earlier: String, later: String): Int {
    val earlierParts = calendarDateParts(earlier, DateCalendarBasis.LEGACY_HYBRID)!!
    val laterParts = calendarDateParts(later, DateCalendarBasis.LEGACY_HYBRID)!!
    val candidate = laterParts.first - earlierParts.first
    val landing = addLegacyCalendarYears(earlier, candidate)!!
    return if (legacyEpochDay(landing)!! > legacyEpochDay(later)!!) candidate - 1 else candidate
}

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
 * Parse a partial DateType value rendered in [format], retaining each exact zero omission marker. Legal
 * omissions form a suffix: day; then month only with day; then year only with month and day. Wider precisions
 * include every narrower legal value and a fully known date. Null means malformed or outside [precision].
 */
internal fun partialDateParts(raw: String, format: String, precision: DatePrecision): Triple<Int, Int, Int>? {
    val (y, mo, d) = partialDateRawParts(raw, format) ?: return null
    if (mo > 12) return null
    if (d > 0 && mo > 0 && y > 0 && d > daysInMonth(y, mo)) return null

    val dayMayBeUnknown = precision != DatePrecision.FULL && precision != DatePrecision.FRAGMENT
    val monthMayBeUnknown = precision == DatePrecision.MONTH_OPTIONAL || precision == DatePrecision.YEAR_OPTIONAL
    val yearMayBeUnknown = precision == DatePrecision.YEAR_OPTIONAL
    if (d == 0 && !dayMayBeUnknown) return null
    if (mo == 0 && (!monthMayBeUnknown || d != 0)) return null
    if (y == 0 && (!yearMayBeUnknown || mo != 0 || d != 0)) return null
    return Triple(y, mo, d)
}

private fun partialDateRawParts(raw: String, format: String): Triple<Int, Int, Int>? {
    val (frag, comps) = dateFragment(format) ?: return null
    val m = Regex("^$frag$").matchEntire(raw) ?: return null
    val g = m.groupValues.drop(1)
    var y = -1; var mo = -1; var d = -1
    comps.forEachIndexed { i, c -> val v = g[i].toInt(); when (c) { 'y' -> y = v; 'M' -> mo = v; 'd' -> d = v } }
    if (y < 0 || mo < 0 || d < 0) return null
    return Triple(y, mo, d)
}

/** Whether an otherwise lexically-shaped partial date violates the day→month→year omission suffix. */
internal fun invalidPartialDateOmissionShape(raw: String, format: String, precision: DatePrecision): Boolean {
    val (year, month, day) = partialDateRawParts(raw, format) ?: return false
    val monthMayBeUnknown = precision == DatePrecision.MONTH_OPTIONAL || precision == DatePrecision.YEAR_OPTIONAL
    val yearMayBeUnknown = precision == DatePrecision.YEAR_OPTIONAL
    return (month == 0 && (!monthMayBeUnknown || day != 0)) ||
        (year == 0 && (!yearMayBeUnknown || month != 0 || day != 0))
}

/** Backward-compatible narrow parser for the original DAY_OPTIONAL owner. */
fun dayOptionalParts(raw: String, format: String): Triple<Int, Int, Int>? =
    partialDateParts(raw, format, DatePrecision.DAY_OPTIONAL)

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
 * Widen a value already completed to its interval START ([completeFragmentToIso]) into the SAME value's
 * interval END, by reading which components [format] actually carries: a format without a month ends in
 * December, a format without a day ends on the completed month's last date (leap-aware); a component the
 * format carries is never touched, so a full format is returned unchanged.
 *
 * This is the endpoint rule of [parseRangeToIso]'s END side, reachable from a value rather than from a raw
 * fragment — the kernel applies the very same `DateAndFormat.addMonthAndDay(INTERVAL_END)` to a stored range's
 * finish and to a `DateRange(start, finish)` CONSTRUCTION's finish, because `constructDateRange` hands both to
 * one `datesAndFormatsToDateRange` (KERNEL-FINDINGS §6, SPEC-2026-08-19-02). Widening the start-completed value
 * is exact: interval-start completion only fills the components the format omits, which are exactly the ones
 * read back here. Null if [format] carries no date component or [startIso] is not an ISO date.
 */
fun intervalEndOfDeclaredFormat(startIso: String, format: String): String? {
    // The kernel's own first line: `addMonthAndDay` returns immediately when the format carries an HOUR
    // (`DatePart.HOUR.isInFormat`), so a time-bearing format is never widened. Mirrored rather than argued
    // away — today `ModelDef.dateFormats` is filled only for `Kind.DATE`, so no such format can reach here,
    // but that is a reachability claim about a neighbouring map, and the guard costs one scan.
    if ('H' in format || 'h' in format) return startIso
    // The common case — a format carrying both sub-year components has nothing to widen — costs one scan and
    // no component list, so the ordinary full-format construction pays nothing for the fragment rule.
    if ('M' in format && 'd' in format) return startIso
    val comps = dateFragment(format)?.second ?: return null
    val (y, m, d) = dateParts(startIso) ?: return null
    val month = if ('M' in comps) m else 12
    return iso(y, month, if ('d' in comps) d else daysInMonth(y, month))
}

/**
 * Resolve a partial DateType value to its concrete ISO boundary. An unknown month selects January/December
 * before an unknown day selects day 1/month-end. An unknown year stays non-relevant and returns null rather
 * than borrowing the formal checker's temporary year 2000.
 */
fun resolvePartialDate(raw: String, format: String, precision: DatePrecision, lastDay: Boolean): String? {
    val (y, storedMonth, storedDay) = partialDateParts(raw, format, precision) ?: return null
    if (y == 0) return null
    val month = if (storedMonth != 0) storedMonth else if (lastDay) 12 else 1
    val day = if (storedDay != 0) storedDay else if (lastDay) daysInMonth(y, month) else 1
    return iso(y, month, day)
}

/** Source-compatible DAY_OPTIONAL overload retained for consumers of the original helper. */
fun resolvePartialDate(raw: String, format: String, lastDay: Boolean): String? =
    resolvePartialDate(raw, format, DatePrecision.DAY_OPTIONAL, lastDay)

/** The canonical ISO `yyyy-MM-dd` for (year, month, day) — zero-padded; the `Date(...)` constructor's value. */
fun isoDate(year: Int, month: Int, day: Int): String = iso(year, month, day)

private fun daysInMonth(year: Int, month: Int): Int = when (month) {
    1, 3, 5, 7, 8, 10, 12 -> 31
    4, 6, 9, 11 -> 30
    else -> if (isGregorianLeapYear(year)) 29 else 28
}

private fun isGregorianLeapYear(year: Int): Boolean =
    year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)

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
fun parseRangeToIso(
    raw: String,
    format: String,
    sep: String,
    baseYear: Int = 2000,
    yearInterpretation: RangeYearInterpretation = RangeYearInterpretation.STANDARD,
): Pair<String, String>? {
    val (frag, comps) = dateFragment(format) ?: return null
    val m = Regex("^$frag${Regex.escape(sep)}$frag$").matchEntire(raw) ?: return null
    val g = m.groupValues.drop(1)
    val startValues = g.take(comps.size)
    val endValues = g.drop(comps.size)
    var startYear = baseYear
    var endYear = baseYear
    if (wrapsAcrossTheYear(startValues, endValues, comps)) {
        // The declaration says WHICH endpoint the base year labels, so the OTHER one moves — measured
        // 2026-08-19 on `01.11-31.01` with base year 2020: FROM keeps the start at 2020 and rolls the end to
        // 2021, TO keeps the end at 2020 and rolls the start back to 2019. (Written the other way round first,
        // and the four accept-only rows could not see it: "no formal error" holds under either direction.)
        when (yearInterpretation) {
            RangeYearInterpretation.FROM -> endYear++
            RangeYearInterpretation.TO -> startYear--
            RangeYearInterpretation.STANDARD -> Unit
        }
    }
    val start = buildRangeEndpoint(startValues, comps, startYear, end = false) ?: return null
    val end = buildRangeEndpoint(endValues, comps, endYear, end = true) ?: return null
    return start to end
}

/**
 * Does this value's END fall before its START once both endpoints are given the same year — the kernel's
 * `DateUtil.isYearNotBaseYear`?
 *
 * Two things about it are load-bearing and were measured rather than assumed (2026-08-19). It applies **only**
 * to a format carrying DAY and MONTH and no YEAR, because the kernel's `completeDateAndFormat` supplements a
 * year in exactly that case: `MM` alone never wraps (its `11/01` stays start-after-end on both engines) and a
 * year-bearing format never does either, so `TO` does not rescue an inverted `yyyy-MM-dd` range. And the probe
 * year is the fixed LEAP year 2000, not the base year, so a `29.02` endpoint parses instead of failing the
 * comparison — a base year of 2001 would otherwise report `01.11-29.02` as unordered.
 */
private fun wrapsAcrossTheYear(startValues: List<String>, endValues: List<String>, comps: List<Char>): Boolean {
    if ('y' in comps || 'd' !in comps || 'M' !in comps) return false
    val start = buildRangeEndpoint(startValues, comps, LEAP_PROBE_YEAR, end = false) ?: return false
    val end = buildRangeEndpoint(endValues, comps, LEAP_PROBE_YEAR, end = false) ?: return false
    return end < start
}

/** The kernel's fixed wrap-probe year — a leap year so a `29.02` endpoint is parseable. */
private const val LEAP_PROBE_YEAR = 2000

/**
 * The year an endpoint of a year-free DATE_RANGE gets for the kernel's DATE-POSITION checks (the always-on
 * Gregorian floor and the opt-in pre-1900 check) when its format names no year — `null` when the format
 * carries one, so the declared year applies.
 *
 * **The kernel supplements the base year in exactly one case, and it is narrower than "no year".**
 * `DateUtil.completeDateAndFormat` appends a year only when `formatContainsOnlyDayMonth(format)` — day AND
 * month present, year absent — so `dd.MM` and `MM-dd` are measured against [baseYear], while a pure `MM`
 * range gets no year appended at all and its endpoint parses at the [RANGE_PARSE_DEFAULT_YEAR] a
 * year-less pattern defaults to. That is above both thresholds, which is why neither position check ever
 * fires on a pure `MM` range whatever the model's base year (KF253, measured on base years 1500/1899/1901
 * with the reversed value as the liveness control).
 *
 * The interpreter's own [parseRangeToIso] deliberately still completes every missing year with [baseYear],
 * because the interval and wrap rules it feeds are measured that way. This helper exists so the two
 * position checks read the year the KERNEL uses for them rather than the one the endpoint was built with.
 */
internal fun rangePositionCheckYear(format: String, baseYear: Int): Int? {
    val comps = dateFragment(format)?.second ?: return null
    if ('y' in comps) return null
    return if ('d' in comps && 'M' in comps) baseYear else RANGE_PARSE_DEFAULT_YEAR
}

/** The year a year-less date pattern parses to when the kernel appends none (`SimpleDateFormat`'s epoch year). */
internal const val RANGE_PARSE_DEFAULT_YEAR = 1970

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
 * Canonicalize a `DATE_TIME` value by whichever of the kind's **two** legal declared formats [format] is.
 *
 * A `DateTimeType` may be declared `yyyy-MM-dd'T'HH:mm:ss` — canonicalized to that ISO shape — or with the
 * degenerate **time-only** `HH:mm:ss`, which omits every calendar component and canonicalizes to `HH:mm:ss`
 * exactly as a TIME field does (KERNEL-FINDINGS §6). No format sniffing is needed: [parseTimeToIso] already
 * returns null for any format carrying `y`/`M`/`d`, so "try the date-bearing reading, else the date-less one"
 * is total and unambiguous.
 *
 * Both the formal check and value resolution route through here so they cannot disagree about whether a cell is
 * readable — they previously each called [parseDateTimeToIso] alone, which returns null for a time-only format,
 * so every such cell was simultaneously reported formally invalid AND resolved to Unknown (IF301).
 */
fun parseDeclaredDateTime(raw: String, format: String): String? =
    parseDateTimeToIso(raw, format) ?: parseTimeToIso(raw, format)

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

/** Which classifier a DECLARED temporal format selects. */
internal enum class TemporalFormatShape { DATE, TIME, DATE_TIME }

/**
 * The classifier [format] selects, read off its **component set alone**.
 *
 * A12's temporal format vocabulary is kind-independent, and so is the value tier: measured over the complete
 * 8-format × 3-kind grid, all 24 cells are model-legal and every format behaves identically on DATE, TIME and
 * DATE_TIME (KF211). So a TIME field declared `yyyy-MM-dd` is a date cell that carries the Gregorian floor, and
 * a DATE field declared `HH:mm:ss` is a clock cell that cannot reach it. The kind decides only the DEFAULT
 * format of a field that declares none.
 *
 * A format carrying no recognizable component is DATE-shaped, which is where an unrecognizable declaration was
 * already routed: it fails every parse, so each of its values is the format error either way.
 */
internal fun temporalFormatShape(format: String): TemporalFormatShape {
    val comps = dateTimeFragment(format)?.second ?: return TemporalFormatShape.DATE
    val date = comps.any { it == 'y' || it == 'M' || it == 'd' }
    val time = comps.any { it == 'H' || it == 'm' || it == 's' }
    return when {
        date && time -> TemporalFormatShape.DATE_TIME
        time -> TemporalFormatShape.TIME
        else -> TemporalFormatShape.DATE
    }
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

/** Whether [s] is a canonical ISO DATE (`yyyy-MM-dd`, no time part) — the shape every resolved DATE value
 *  carries; the date-comparison instant dispatch keys on it. */
fun isIsoDate(s: String): Boolean = ISO.matches(s)

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

/** Canonical wall rendering of [instant] under the calendar provenance of the producing expression. */
internal fun isoFromCalendarInstant(instant: Long, tz: ModelTimeZone, basis: DateCalendarBasis): String {
    val local = instantToLocal(instant, tz)
    val date = when (basis) {
        DateCalendarBasis.PROLEPTIC_GREGORIAN -> isoFromEpochDay(local.floorDiv(86_400L))
        DateCalendarBasis.LEGACY_HYBRID -> isoFromLegacyEpochDay(local.floorDiv(86_400L))
    }
    val time = local.mod(86_400L)
    return "$date" + "T" + two((time / 3_600L).toInt()) + ":" +
        two(((time % 3_600L) / 60L).toInt()) + ":" + two((time % 60L).toInt())
}

/** Whether a well-formed local date-time falls in [tz]'s spring-forward gap (a wall time that does not
 *  exist) — the kernel's `datumFormatFalsch` for a stored value (probe-verified, KERNEL-FINDINGS §6). */
fun isGapLocal(iso: String, tz: ModelTimeZone): Boolean =
    tz != ModelTimeZone.UTC && epochSecond(iso)?.let { localToInstant(it, tz) == null } == true

/**
 * The landing instant of a `Calendar.DAY_OF_MONTH` add — the kernel's `BedingungsOperatorHelper.addTime` day
 * arm, which is `GregorianCalendar.add(DAY_OF_MONTH, n)` on a model-zone calendar.
 *
 * The mechanism is **source-instant-based and direction-INDEPENDENT** (SPEC-2026-07-29-01; the earlier account
 * here resolved a nominal wall LABEL by direction of travel, which agrees on adjacent ±1-day steps and
 * diverges once the source and the landing sit in different offset seasons):
 *
 *  1. add the raw elapsed span — `[sourceInstant] + n·86 400`, so the source's offset AND its sub-second
 *     remainder ride along;
 *  2. if the offset at that instant differs from the source's, **re-fit** by the offset delta so the wall
 *     clock is preserved;
 *  3. keep the re-fit **only when it lands on the EXPECTED civil date** — the source's civil date plus [n] —
 *     otherwise the raw instant stands.
 *
 * Step 3 is judged against the expected date, not against the raw landing's own date, and the two differ exactly
 * when the source sits within the offset delta of midnight: `AddDays(2024-10-27T00:30, 1)` raw-lands on
 * `27T23:30` CET, and its re-fit `28T00:30` is kept because the 28th is the date asked for, while
 * `AddDays(2024-03-30T23:30, 1)` raw-lands on `04-01T00:30` CEST and its re-fit `03-31T23:30` is kept for the same
 * reason. Comparing against the raw date rejected both re-fits (SPEC-2026-10-02-01's row and our spring twin,
 * `adapter.laws.ComputedDateTimeJoinDiffTest`, both kernel strategies).
 *
 * Two adjacent additions can therefore select different sides of one overlap: `AddDays` reaching 1916-10-01
 * from a CEST September source lands on the daylight side, while a 214-day add from a CET March source lands
 * on the fresh CET side — same civil label, one hour apart.
 */
fun wallDayLandingInstant(sourceInstant: Long, n: Int, tz: ModelTimeZone): Long {
    val raw = sourceInstant + n * 86_400L
    if (tz == ModelTimeZone.UTC) return raw
    val sourceOffset = berlinOffsetSeconds(sourceInstant)
    val rawOffset = berlinOffsetSeconds(raw)
    if (rawOffset == sourceOffset) return raw
    val refit = raw + (sourceOffset - rawOffset)
    val expectedDay = instantToLocal(sourceInstant, tz).floorDiv(86_400L) + n
    val refitDay = instantToLocal(refit, tz).floorDiv(86_400L)
    return if (refitDay == expectedDay) refit else raw
}

/**
 * Signed model-zone calendar-day count from [a] to [b]. Each operand is a canonical local DATE or DATE_TIME.
 * The count follows the kernel's legacy calendar stepping, including a special-hour landing under Berlin;
 * sub-day differences remain instant arithmetic in their own operations.
 */
internal fun differenceInCalendarDays(
    a: String,
    b: String,
    tz: ModelTimeZone,
    aBasis: DateCalendarBasis = DateCalendarBasis.PROLEPTIC_GREGORIAN,
    bBasis: DateCalendarBasis = DateCalendarBasis.PROLEPTIC_GREGORIAN,
): Int? {
    val aInstant = calendarOperandInstant(a, tz, aBasis) ?: return null
    val bInstant = calendarOperandInstant(b, tz, bBasis) ?: return null
    return differenceInCalendarDays(aInstant, bInstant, tz)
}

/** The instant of a canonical local DATE or DATE_TIME operand. */
internal fun calendarOperandInstant(
    value: String,
    tz: ModelTimeZone,
    basis: DateCalendarBasis = DateCalendarBasis.PROLEPTIC_GREGORIAN,
): Long? {
    val local = if ('T' in value) {
        val date = value.substringBefore('T')
        val time = TIME_OF_DAY.matchEntire(value.substringAfter('T')) ?: return null
        val day = calendarEpochDay(date, basis) ?: return null
        val hour = time.groupValues[1].toLong()
        val minute = time.groupValues[2].toLong()
        val second = time.groupValues[3].toLong()
        if (hour > 23L || minute > 59L || second > 59L) return null
        day * 86_400L + hour * 3_600L + minute * 60L + second
    } else {
        calendarEpochDay(value, basis)?.times(86_400L)
    }
    return local?.let { localToInstant(it, tz) }
}

/** The instant-preserving variant used by the expression evaluator for composed DATE_TIME operands. */
internal fun differenceInCalendarDays(aInstant: Long, bInstant: Long, tz: ModelTimeZone): Int =
    calendarDayDifferenceDetail(aInstant, bInstant, tz).days

/** Test-visible structural performance evidence for the bounded seed residual. */
internal data class CalendarDayDifferenceDetail(val days: Int, val residualSteps: Int)

internal fun calendarDayDifferenceDetail(
    aInstant: Long,
    bInstant: Long,
    tz: ModelTimeZone,
): CalendarDayDifferenceDetail {
    val forward = aInstant <= bInstant
    val earlier = if (forward) aInstant else bInstant
    val later = if (forward) bInstant else aInstant

    val wholeYears = wholeLegacyCalendarYears(earlier, later, tz)
    var count = wholeYears * 365
    var residualSteps = 0
    var cursor = if (count == 0) earlier
        else wallDayLandingInstant(earlier, count, tz)
    // The repeated +1 is intentionally stateful. A Berlin gap landing can change the clock (02:30→01:30),
    // and subsequent Calendar.DAY additions retain that changed clock; replacing the loop by one ordinal jump
    // is observably wrong. The runtime's 365-day year seed bounds this residual to the accumulated leap days
    // (fewer than 2,800 successful iterations over the full four-digit label domain, including the final
    // partial year), rather than total elapsed days.
    while (true) {
        val next = wallDayLandingInstant(cursor, 1, tz)
        if (next > later) break
        cursor = next
        count++
        residualSteps++
    }
    return CalendarDayDifferenceDetail(if (forward) count else -count, residualSteps)
}

/** Complete legacy-calendar years between ordered instants — the runtime's lower-bound seed for day diff. */
private fun wholeLegacyCalendarYears(earlier: Long, later: Long, tz: ModelTimeZone): Int {
    val earlierDate = legacyDateAtInstant(earlier, tz)
    val laterDate = legacyDateAtInstant(later, tz)
    val candidate = laterDate.first - earlierDate.first
    if (candidate == 0) return 0
    return if (legacyCalendarYearLandingInstant(earlier, candidate, tz) > later) candidate - 1 else candidate
}

/** Model-zone legacy civil date fields at [instant]. */
private fun legacyDateAtInstant(instant: Long, tz: ModelTimeZone): Triple<Int, Int, Int> {
    val localDay = instantToLocal(instant, tz).floorDiv(86_400L)
    return dateParts(isoFromLegacyEpochDay(localDay))!!
}

/** A `Calendar.YEAR` landing, including the runtime's February-28 correction and retained time-of-day. */
private fun legacyCalendarYearLandingInstant(instant: Long, years: Int, tz: ModelTimeZone): Long {
    val local = instantToLocal(instant, tz)
    val sourceDate = isoFromLegacyEpochDay(local.floorDiv(86_400L))
    val sourceParts = dateParts(sourceDate)!!
    val shiftedDate = addLegacyCalendarYears(sourceDate, years)
        ?: error("legacy calendar year landing overflow: $sourceDate + $years years")
    val targetYear = sourceParts.first + years
    val sourceTime = local.mod(86_400L)
    val shiftedTime = if (sourceParts.second == 2 && sourceParts.third == 28 &&
        isGregorianLeapYear(sourceParts.first) && !isGregorianLeapYear(targetYear)) 0L else sourceTime
    val nominal = legacyEpochDay(shiftedDate)!! * 86_400L + shiftedTime
    return fieldMutationLandingInstant(nominal, tz)
}

/**
 * The landing instant of a `Calendar.MONTH`/`Calendar.YEAR` mutation that produced the civil DATE label
 * [isoDate] — midnight of that label, resolved by [fieldMutationLandingInstant]. A Date-valued month/year
 * shift retains this so a consumer observes the instant the mutation selected rather than re-resolving the
 * label (SPEC-2026-07-29-01). Null when the label is not a real date under [basis].
 */
internal fun fieldMutationInstantOfDate(isoDate: String, tz: ModelTimeZone, basis: DateCalendarBasis): Long? {
    val day = calendarEpochDay(isoDate, basis) ?: return null
    return fieldMutationLandingInstant(day * 86_400L, tz)
}

/**
 * The instant of a FRESHLY CONSTRUCTED / parsed DATE label — midnight resolved the way the kernel's
 * non-lenient zone parse resolves it ([localToInstant]): an overlap takes the smaller (after) offset, i.e.
 * the LATER copy. Retained on a constructed Date so a comparison against a shifted Date can observe that the
 * two selected different sides of one overlap. Null for a gap label (which the kernel rejects formally).
 */
internal fun freshDateInstant(isoDate: String, tz: ModelTimeZone, basis: DateCalendarBasis): Long? {
    val day = calendarEpochDay(isoDate, basis) ?: return null
    return localToInstant(day * 86_400L, tz)
}

/**
 * A `Calendar.MONTH` / `Calendar.YEAR` landing — resolved at COMPUTE time from the nominal civil label alone
 * and INDEPENDENTLY of the sign of the mutation (SPEC-2026-07-29-01). Unlike the day arm
 * ([wallDayLandingInstant]) this carries no source instant: `GregorianCalendar` re-resolves the mutated
 * fields, so an **overlap** label takes the LATER instant and a **gap** label normalizes to the POST-gap
 * label (`02:30` → `03:30`, one hour past the transition).
 *
 * Both halves are now differentially separated. The GAP half:
 * `DifferenceInDays(2003-03-31T02:30, 2024-03-31T03:00)` is **7670** on both kernel strategies, because the
 * 21-year candidate's post-gap landing falls after the later endpoint and is rejected, where the previous
 * direction-selected pre-gap landing admitted it and produced 7671. The OVERLAP half: direct Date equality
 * over the one ambiguous midnight — `AddMonths(Date("1","9","1916"), 1) == Date("1","10","1916")` and the
 * `AddYears` twin are both **true** while the `AddDays` shape is **false**, so taking the LATER copy here is
 * observed rather than assumed (`ConstructedDateInstantDiffTest`). A `DifferenceInDays` construction cannot
 * show the overlap half — its day loop only ever adds, so a seed difference self-corrects — which is why the
 * observer has to be a comparison that introduces no second calendar mutation.
 */
private fun fieldMutationLandingInstant(nominal: Long, tz: ModelTimeZone): Long {
    if (tz == ModelTimeZone.UTC) return nominal

    var latestConsistent = Long.MIN_VALUE
    var gapBeforeOffset = Long.MIN_VALUE
    for (offset in BERLIN_OFFSETS) {
        val instant = nominal - offset
        val actualOffset = berlinOffsetSeconds(instant)
        if (actualOffset == offset) latestConsistent = maxOf(latestConsistent, instant)
        else if (actualOffset > offset) gapBeforeOffset = offset   // the PRE-transition offset brackets the gap
    }
    if (latestConsistent != Long.MIN_VALUE) return latestConsistent
    // The gap: no consistent instant. Assuming the pre-gap offset lands one transition-delta past the gap,
    // which IS the post-gap wall label the lenient field resolution produces.
    check(gapBeforeOffset != Long.MIN_VALUE) {
        "Berlin field-mutation landing has no consistent or bracketing offset for local=$nominal"
    }
    return nominal - gapBeforeOffset
}
