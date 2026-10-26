package io.github.mbackschat.a12.dm.interpreter.eval

import io.github.mbackschat.a12.dm.interpreter.model.DatePrecision
import io.github.mbackschat.a12.dm.interpreter.model.RangeYearInterpretation
import io.github.mbackschat.a12.dm.interpreter.model.ModelTimeZone

/**
 * Pure date arithmetic on ISO `yyyy-MM-dd` strings — no `java.time`/luxon, so it stays in commonMain and
 * runs identically on JVM and JS. Evaluation reads every date label on A12's Julian–Gregorian hybrid calendar
 * ([calendarDateParts], [legacyEpochDay]); [epochDay] is the proleptic day number, the calendar of an
 * `EvalClock` label and of the zone-gap check, which never meet a date below the cutover. The hybrid calendar below and the model-zone calendar steps in
 * `ZonedInstants.kt` are explicit seams rather than implicit host date-library behavior.
 */
// Four or more year digits: calendar arithmetic keeps a five-digit intermediate for the target check to classify
// (SPEC-2026-10-04-01, `adapter.laws.CalendarTargetCompletionDiffTest`); stored values are parsed by their format.
private val ISO = Regex("""(\d{4,})-(\d{2})-(\d{2})""")

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

/**
 * The (year, month, day) of a date label on A12's calendar, or null when the label is not a real date there.
 *
 * A12 holds every date as a `java.util.Date` instant read through a default `GregorianCalendar`, which is Julian
 * before 1582-10-15: a stored value, a literal, a clock reading and a constructed `Date(...)` alike. So the evaluator
 * reads every label on that hybrid calendar; a proleptic reading would differ only below the cutover, where
 * `1500-02-29` is real and `1582-10-05` is not (KF588, `adapter.laws.StoredDateHybridCalendarDiffTest`).
 */
internal fun calendarDateParts(iso: String): Triple<Int, Int, Int>? {
    val parts = dateParts(iso) ?: return null
    return parts.takeIf { isParsedRealDate(parts.first, parts.second, parts.third) }
}

/** A legacy-calendar day shift, including the 1582-10-04 → 1582-10-15 cutover step. */
internal fun addLegacyCalendarDays(iso: String, n: Int): String? =
    legacyEpochDay(iso)?.let { isoFromLegacyEpochDay(it + n) }

/** A legacy `Calendar.MONTH` shift. The target day is clamped; a target in the cutover hole normalizes ahead. */
internal fun addLegacyCalendarMonths(iso: String, n: Int): String? {
    val (year, month, day) = calendarDateParts(iso) ?: return null
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
    val (year, month, day) = calendarDateParts(iso) ?: return null
    val targetYear = year + n
    var result = normalizedLegacyTarget(targetYear, month, minOf(day, legacyDaysInMonth(targetYear, month)))
        ?: return null
    val resultParts = calendarDateParts(result) ?: return null
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
internal val TIME_OF_DAY = Regex("""(\d{2}):(\d{2}):(\d{2})""")

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
    return date + "T" + twoDigits((tod / 3_600).toInt()) + ":" + twoDigits(((tod % 3_600) / 60).toInt()) + ":" + twoDigits((tod % 60).toInt())
}

/** ISO date-time [seconds] after [iso] (carrying across days), or null if [iso] is malformed. */
fun addDateTimeSeconds(iso: String, seconds: Long): String? = epochSecond(iso)?.let { isoFromEpochSecond(it + seconds) }

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

/**
 * Whether a date A12 PARSES — a stored value, a range endpoint, a completed fragment or a date constant — names
 * a real day (KERNEL-FINDINGS KF588). A12 parses through a non-lenient `SimpleDateFormat`, whose calendar is the
 * same hybrid one `Date(...)` constructs on, so the ten labels 1582-10-05..14 are unreal, a Julian leap day such
 * as 1500-02-29 is real, and so is no day of year 0. From 1582-10-15 it agrees with the proleptic calendar.
 */
internal fun isParsedRealDate(year: Int, month: Int, day: Int): Boolean =
    legacyCalendarSystem(year, month, day) != null && day <= legacyDaysInMonth(year, month)

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
internal fun legacyEpochDay(iso: String): Long? {
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
internal fun isoFromLegacyEpochDay(epochDay: Long): String {
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
    val earlierParts = calendarDateParts(earlier)!!
    val laterParts = calendarDateParts(later)!!
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
    if (d > 0 && mo > 0 && y > 0 && !isParsedRealDate(y, mo, d)) return null

    val dayMayBeUnknown = precision != DatePrecision.FULL && precision != DatePrecision.FRAGMENT
    val monthMayBeUnknown = precision == DatePrecision.MONTH_OPTIONAL || precision == DatePrecision.YEAR_OPTIONAL
    val yearMayBeUnknown = precision == DatePrecision.YEAR_OPTIONAL
    if (d == 0 && !dayMayBeUnknown) return null
    if (mo == 0 && (!monthMayBeUnknown || d != 0)) return null
    if (y == 0 && (!yearMayBeUnknown || mo != 0 || d != 0)) return null
    return Triple(y, mo, d)
}

private fun partialDateRawParts(raw: String, format: String): Triple<Int, Int, Int>? {
    val pattern = dateFragment(format) ?: return null
    val g = pattern.capture(raw) ?: return null
    val comps = pattern.components
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
 * a missing day-in-month uses [baseYear]'s leap-ness on the hybrid calendar (so `MM-dd` = `02-29` is real iff
 * [baseYear] is a leap year there, Julian below 1582 — matching the kernel completing with the base year before
 * the realness check).
 *
 * Both kernel strategies read a fragment field through this single completion, so canonicalizing a fragment
 * to this ISO date makes ALL the ordinary date machinery (comparison, `Add*`, extraction) match both — the
 * interpreter never re-enforces the authoring-time comparability/granularity gates (KERNEL-FINDINGS §6).
 */
fun completeFragmentToIso(raw: String, format: String, baseYear: Int): String? {
    val pattern = dateFragment(format) ?: return null
    val g = pattern.capture(raw) ?: return null
    val comps = pattern.components
    var y = baseYear; var mo = 1; var d = 1
    comps.forEachIndexed { i, c -> val v = g[i].toInt(); when (c) { 'y' -> y = v; 'M' -> mo = v; 'd' -> d = v } }
    if (!isParsedRealDate(y, mo, d)) return null
    return iso(y, mo, d)
}

/**
 * Widen a value already completed to its interval START ([completeFragmentToIso]) into the SAME value's
 * interval END, by reading which components [format] actually carries: a format without a month ends in
 * December, a format without a day ends on the completed month's last date (hybrid leap year); a component the
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
    val comps = dateFragment(format)?.components ?: return null
    val (y, m, d) = dateParts(startIso) ?: return null
    val month = if ('M' in comps) m else 12
    return iso(y, month, if ('d' in comps) d else legacyDaysInMonth(y, month))
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
    val day = if (storedDay != 0) storedDay else if (lastDay) legacyDaysInMonth(y, month) else 1
    return iso(y, month, day)
}

/** Source-compatible DAY_OPTIONAL overload retained for consumers of the original helper. */
fun resolvePartialDate(raw: String, format: String, lastDay: Boolean): String? =
    resolvePartialDate(raw, format, DatePrecision.DAY_OPTIONAL, lastDay)

/** The canonical ISO `yyyy-MM-dd` for (year, month, day) — zero-padded; the `Date(...)` constructor's value. */
fun isoDate(year: Int, month: Int, day: Int): String = iso(year, month, day)

internal fun isGregorianLeapYear(year: Int): Boolean =
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
 *
 * [completeEndToLastDay] `false` completes the END like the start instead — A12's document conversion, which builds
 * both endpoints with a missing month or day of 1 before any runtime completion applies (KERNEL-FINDINGS KF540).
 */
fun parseRangeToIso(
    raw: String,
    format: String,
    sep: String,
    baseYear: Int = 2000,
    yearInterpretation: RangeYearInterpretation = RangeYearInterpretation.STANDARD,
    completeEndToLastDay: Boolean = true,
): Pair<String, String>? {
    val pattern = dateFragment(format) ?: return null
    val comps = pattern.components
    val g = pattern.around(sep).capture(raw) ?: return null
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
    val end = buildRangeEndpoint(endValues, comps, endYear, end = completeEndToLastDay) ?: return null
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
 * The year the kernel's FORMAL check completes a year-free date with — for a scalar DATE value, a computed
 * target's rendered attempt, and a DATE_RANGE endpoint's position checks alike, since all three pass through
 * `FormatDefinitionDatum.validiereFormat` (SPEC-2026-10-04-01, `adapter.laws.CalendarTargetCompletionDiffTest`).
 * `null` when the format carries a year, so the declared year applies. Evaluation completes the year separately
 * (`DateUtil.getValueAsDate`), with the Base Year, which is why a month-only value can be formally admitted and
 * still evaluate below the floor.
 *
 * **The kernel supplements the base year in exactly one case, and it is narrower than "no year".**
 * `DateUtil.completeDateAndFormat` appends a year only when `formatContainsOnlyDayMonth(format)` — day AND
 * month present, year absent — so `dd.MM` and `MM-dd` are measured against [baseYear], while a pure `MM`
 * range gets no year appended at all and its endpoint parses at the [RANGE_PARSE_DEFAULT_YEAR] a
 * year-less pattern defaults to. That is above both thresholds, which is why neither position check ever
 * fires on a pure `MM` range whatever the model's base year (KF276, measured on base years 1500/1899/1901
 * with the reversed value as the liveness control).
 *
 * The interpreter's own [parseRangeToIso] deliberately still completes every missing year with [baseYear],
 * because the interval and wrap rules it feeds are measured that way. This helper exists so the formal checks
 * read the year the KERNEL uses for them rather than the one evaluation builds the value with.
 */
internal fun formalCompletionYear(format: String, baseYear: Int): Int? {
    val comps = dateFragment(format)?.components ?: return null
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
 * end on that month's real last day (hybrid leap year, so February 1500 ends on the 29th).
 */
private fun buildRangeEndpoint(values: List<String>, comps: List<Char>, baseYear: Int, end: Boolean): String? {
    var y = baseYear; var mo = -1; var d = -1
    comps.forEachIndexed { i, c -> val v = values[i].toInt(); when (c) { 'y' -> y = v; 'M' -> mo = v; 'd' -> d = v } }
    if (mo == -1) mo = if (end) 12 else 1                          // missing month → interval bound
    if (mo < 1 || mo > 12) return null                            // a PRESENT month must be real
    if (d == -1) d = if (end) legacyDaysInMonth(y, mo) else 1     // missing day → interval bound (hybrid leap year)
    if (!isParsedRealDate(y, mo, d)) return null                  // a PRESENT day must be real
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
    val pattern = dateFragment(format) ?: return null
    return buildIso(pattern.capture(raw) ?: return null, pattern.components)
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

/** [format] as one digit run per `y`/`M`/`d` run (exact width = run length) plus the component order; every
 *  other character is literal. Null if [format] carries no date component. */
internal fun dateFragment(format: String): FormatPattern? {
    val slots = mutableListOf<FormatSlot>()
    val comps = mutableListOf<Char>()
    var i = 0
    while (i < format.length) {
        val c = format[i]
        if (c == 'y' || c == 'M' || c == 'd') {
            var j = i
            while (j < format.length && format[j] == c) j++
            slots += FormatSlot.Digits(j - i)
            comps.add(c)
            i = j
        } else {
            slots += FormatSlot.Literal(c)
            i++
        }
    }
    return if (comps.isEmpty()) null else FormatPattern(slots, comps)
}

/** One position of a [FormatPattern]: exactly [Digits.width] ASCII digits, or one literal character. */
internal sealed interface FormatSlot {
    data class Digits(val width: Int) : FormatSlot
    data class Literal(val char: Char) : FormatSlot
}

/**
 * A temporal format read as fixed-width digit runs and literal characters, matched by walking the value.
 *
 * It replaces compiling the format into a regex on every parsed value — measured 2026-10-26 on the JVM at
 * ~530 ns per `dd.MM.yyyy` parse against ~100 ns for the match alone. Every slot has a fixed width, so the
 * regex had exactly one alignment and this walk reads the same one; `FormatPatternRegexEquivalenceTest`
 * holds it to that regex on both JVM and JS, including non-ASCII digits and a trailing line terminator.
 */
internal class FormatPattern(private val slots: List<FormatSlot>, val components: List<Char>) {
    /** The digit runs of [raw] in slot order, or null unless [raw] is exactly this pattern. */
    fun capture(raw: String): List<String>? {
        val runs = ArrayList<String>(components.size)
        var at = 0
        for (slot in slots) {
            when (slot) {
                is FormatSlot.Digits -> {
                    val end = at + slot.width
                    if (end > raw.length) return null
                    for (i in at until end) if (raw[i] !in '0'..'9') return null
                    runs += raw.substring(at, end)
                    at = end
                }
                is FormatSlot.Literal -> {
                    if (at >= raw.length || raw[at] != slot.char) return null
                    at++
                }
            }
        }
        return if (at == raw.length) runs else null
    }

    /** Two values of this pattern around the literal [separator], as a range is written. */
    fun around(separator: String): FormatPattern =
        FormatPattern(slots + separator.map { FormatSlot.Literal(it) } + slots, components + components)
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
    val pattern = dateTimeFragment(format) ?: return null
    return buildIsoDateTime(pattern.capture(raw) ?: return null, pattern.components)
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
    val pattern = dateTimeFragment(format) ?: return null
    val comps = pattern.components
    if (comps.any { it == 'y' || it == 'M' || it == 'd' }) return null
    val g = pattern.capture(raw) ?: return null
    var h = 0; var mi = 0; var s = 0
    comps.forEachIndexed { i, c -> val v = g[i].toInt(); when (c) { 'H' -> h = v; 'm' -> mi = v; 's' -> s = v } }
    if (h > 23 || mi > 59 || s > 59) return null
    return twoDigits(h) + ":" + twoDigits(mi) + ":" + twoDigits(s)
}

/**
 * Like [dateFragment] but also recognizes the time runs `H`/`m`/`s`, and treats a single-quoted section
 * `'…'` as a SimpleDateFormat-style literal (the quotes are delimiters, `''` is a literal quote) — so a
 * `yyyy-MM-dd'T'HH:mm:ss` format matches a rendered `2024-06-01T12:30:00` (no quotes in the value). Null
 * if [format] carries no date/time component.
 */
internal fun dateTimeFragment(format: String): FormatPattern? {
    val slots = mutableListOf<FormatSlot>()
    val comps = mutableListOf<Char>()
    var i = 0
    while (i < format.length) {
        val c = format[i]
        when {
            c == 'y' || c == 'M' || c == 'd' || c == 'H' || c == 'm' || c == 's' -> {
                var j = i
                while (j < format.length && format[j] == c) j++
                slots += FormatSlot.Digits(j - i)
                comps.add(c)
                i = j
            }
            c == '\'' -> {                                   // a quoted literal section: 'T' → literal T, '' → '
                i++
                while (i < format.length && format[i] != '\'') { slots += FormatSlot.Literal(format[i]); i++ }
                if (i < format.length) i++                   // consume the closing quote
            }
            else -> { slots += FormatSlot.Literal(c); i++ }
        }
    }
    return if (comps.isEmpty()) null else FormatPattern(slots, comps)
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
    // The components [dateTimeFragment] reads, without building its pattern: this runs on every cell resolve.
    var date = false
    var time = false
    var quoted = false
    for (c in format) {
        when {
            c == '\'' -> quoted = !quoted
            quoted -> Unit
            c == 'y' || c == 'M' || c == 'd' -> date = true
            c == 'H' || c == 'm' || c == 's' -> time = true
        }
    }
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
    if (!isParsedRealDate(y, mo, d) || h > 23 || mi > 59 || s > 59) return null
    return iso(y, mo, d) + "T" + twoDigits(h) + ":" + twoDigits(mi) + ":" + twoDigits(s)
}

internal fun twoDigits(n: Int): String = n.toString().padStart(2, '0')

/** Assemble ISO `yyyy-MM-dd` from a date fragment's captured component [values] in [comps] order; null if out of range. */
private fun buildIso(values: List<String>, comps: List<Char>): String? {
    var y = -1; var mo = -1; var d = -1
    comps.forEachIndexed { idx, c -> when (c) { 'y' -> y = values[idx].toInt(); 'M' -> mo = values[idx].toInt(); 'd' -> d = values[idx].toInt() } }
    if (!isParsedRealDate(y, mo, d)) return null
    return iso(y, mo, d)
}

/** Whether [s] is a canonical ISO date-time (`yyyy-MM-dd'T'HH:mm:ss`) — the shape every resolved DATE_TIME
 *  value carries; the datetime-comparison dispatch keys on it. */
fun isIsoDateTime(s: String): Boolean = ISO_DATE_TIME.matches(s)

/** Whether [s] is a canonical ISO DATE (`yyyy-MM-dd`, no time part) — the shape every resolved DATE value
 *  carries; the date-comparison instant dispatch keys on it. */
fun isIsoDate(s: String): Boolean = ISO.matches(s)
