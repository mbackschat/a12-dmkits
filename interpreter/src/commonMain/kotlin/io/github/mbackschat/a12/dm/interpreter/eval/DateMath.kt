package io.github.mbackschat.a12.dm.interpreter.eval

import io.github.mbackschat.a12.dm.interpreter.model.DatePrecision
import io.github.mbackschat.a12.dm.interpreter.model.RangeYearInterpretation
import io.github.mbackschat.a12.dm.interpreter.model.ModelTimeZone

/**
 * Pure date arithmetic on ISO `yyyy-MM-dd` strings — no `java.time`/luxon, so it stays in commonMain and
 * runs identically on JVM and JS. Every label is on the proleptic ISO calendar, the calendar of A12 31.1.1's
 * `java.time` runtime ([epochDay]); the one place the older hybrid calendar survives is the parse fallback
 * A12 keeps for a Julian leap day ([resolveParsedDate]). The model-zone operations live in `ZonedInstants.kt`.
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
 * Whether `Date(day, month, year)` names a real day: A12 31.1.1 constructs it with `LocalDate.of`, so the calendar
 * is proleptic ISO — 1582-10-05 is a day and 1500-02-29 is not, and year 0 is (`DateUtil.createDate`, source-read at
 * eec9134). No zone removes a whole local date in the supported profiles, so the zone never decides it.
 */
internal fun isConstructibleDate(year: Int, month: Int, day: Int): Boolean =
    month in 1..12 && day in 1..daysInMonth(year, month)

/** Whether (year, month, day) is a real proleptic date with a year of era, which is what a strict
 *  `yyyy` parse admits: year 0 is not one. */
internal fun isRealDate(year: Int, month: Int, day: Int): Boolean =
    year >= 1 && month in 1..12 && day in 1..daysInMonth(year, month)

/** The proleptic length of [month] in [year]. */
internal fun daysInMonth(year: Int, month: Int): Int = when (month) {
    1, 3, 5, 7, 8, 10, 12 -> 31
    4, 6, 9, 11 -> 30
    else -> if (isGregorianLeapYear(year)) 29 else 28
}

/**
 * The (year, month, day) a date A12 PARSES resolves to — a stored value, a range endpoint, a completed fragment or a
 * temporal constant — or null when the parse fails (KERNEL-FINDINGS KF665).
 *
 * A12 31.1.1 parses strictly on the proleptic calendar first, so 1582-10-05 is a real day. Only when that fails does it
 * retry with the old non-lenient hybrid parser, which admits exactly one more class of label: a Julian leap day, a
 * February 29 in a century year the Gregorian rule skips, before the 1582 cutover. That retry's instant is the Julian
 * day, so it reads back as a proleptic date ten or so days later (1500-02-29 is 1500-03-10), and A12 discards the
 * retry under Europe/Berlin for anything before 1893-04-01, which is every Julian leap day. Probe-measured on both
 * strategies: a stored 1500-02-29 is `datumFalsch` under UTC and `datumFormatFalsch` under Berlin, and the UTC constant
 * `"29.02.1500"` counts zero days to `"10.03.1500"`.
 */
internal fun resolveParsedDate(year: Int, month: Int, day: Int, timeZone: ModelTimeZone): Triple<Int, Int, Int>? = when {
    isRealDate(year, month, day) -> Triple(year, month, day)
    isJulianLeapDay(year, month, day) && timeZone != ModelTimeZone.EUROPE_BERLIN -> {
        val (y, m, d) = civilOfEpochDay(julianEpochDay(year, month, day))
        Triple(y, m, d)
    }
    else -> null
}

/** A February 29 the hybrid calendar has and the proleptic one lacks: a Julian leap year before the 1582 cutover
 *  that the Gregorian rule makes common. */
internal fun isJulianLeapDay(year: Int, month: Int, day: Int): Boolean =
    month == 2 && day == 29 && year in 1..1581 && year % 4 == 0 && !isGregorianLeapYear(year)

/** Whether [raw], read in the date or date-time [format] without any realness check, names a Julian leap day. */
internal fun namesJulianLeapDay(raw: String, format: String): Boolean {
    val pattern = dateTimeFragment(format) ?: return false
    val values = pattern.capture(raw) ?: return false
    var y = -1; var mo = -1; var d = -1
    pattern.components.forEachIndexed { i, c -> when (c) { 'y' -> y = values[i].toInt(); 'M' -> mo = values[i].toInt(); 'd' -> d = values[i].toInt() } }
    return isJulianLeapDay(y, mo, d)
}

private const val UNIX_EPOCH_JULIAN_DAY = 2_440_588L

/** The epoch day of a JULIAN-calendar label (the Julian-day-number formula for the Julian calendar). */
private fun julianEpochDay(year: Int, month: Int, day: Int): Long {
    val a = (14 - month) / 12
    val y = year.toLong() + 4_800L - a
    val m = month + 12 * a - 3
    return day + (153L * m + 2L) / 5L + 365L * y + y / 4L - 32_083L - UNIX_EPOCH_JULIAN_DAY
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
internal fun partialDateParts(
    raw: String,
    format: String,
    precision: DatePrecision,
    timeZone: ModelTimeZone,
): Triple<Int, Int, Int>? {
    val (y, mo, d) = partialDateRawParts(raw, format) ?: return null
    if (mo > 12) return null
    // A fully known value is parsed as a date, with A12's parse fallback ([resolveParsedDate]).
    if (d > 0 && mo > 0 && y > 0) return resolveParsedDate(y, mo, d, timeZone)

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
fun dayOptionalParts(raw: String, format: String, timeZone: ModelTimeZone): Triple<Int, Int, Int>? =
    partialDateParts(raw, format, DatePrecision.DAY_OPTIONAL, timeZone)

/**
 * Complete a **date FRAGMENT** value (rendered in its `getFormatOfFragment` [format], one of `yyyy-MM`,
 * `yyyy`, `MM`, `MM-dd`) to its **earliest** concrete ISO `yyyy-MM-dd` date — the kernel's `feldWertAlsDatum`
 * INTERVAL_START completion: a missing MONTH → `01`, a missing DAY → `01`, a missing YEAR → [baseYear] (the
 * model's declared base year, else the kernel default 2000). Returns null when the value does not match its
 * format, has an out-of-range month, or is an impossible calendar day (the kernel's `datumFormatFalsch`);
 * the completed value is parsed like any date ([resolveParsedDate]), so `MM-dd` = `02-29` is real iff [baseYear] is a
 * leap year — the kernel completes with the base year before it parses (`DateUtil.createDateFromDateFragment`).
 *
 * Both kernel strategies read a fragment field through this single completion, so canonicalizing a fragment
 * to this ISO date makes ALL the ordinary date machinery (comparison, `Add*`, extraction) match both — the
 * interpreter never re-enforces the authoring-time comparability/granularity gates (KERNEL-FINDINGS §6).
 */
fun completeFragmentToIso(raw: String, format: String, baseYear: Int, timeZone: ModelTimeZone): String? {
    val pattern = dateFragment(format) ?: return null
    val g = pattern.capture(raw) ?: return null
    val comps = pattern.components
    var y = baseYear; var mo = 1; var d = 1
    comps.forEachIndexed { i, c -> val v = g[i].toInt(); when (c) { 'y' -> y = v; 'M' -> mo = v; 'd' -> d = v } }
    return resolveParsedDate(y, mo, d, timeZone)?.let { (ry, rm, rd) -> iso(ry, rm, rd) }
}

/**
 * Widen a value already completed to its interval START ([completeFragmentToIso]) into the SAME value's
 * interval END, by reading which components [format] actually carries: a format without a month ends in
 * December, a format without a day ends on the completed month's last date (proleptic, as `TemporalAdjusters.lastDayOfMonth`); a component the
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
    return iso(y, month, if ('d' in comps) d else daysInMonth(y, month))
}

/**
 * Resolve a partial DateType value to its concrete ISO boundary. An unknown month selects January/December
 * before an unknown day selects day 1/month-end. An unknown year stays non-relevant and returns null rather
 * than borrowing the formal checker's temporary year 2000.
 */
fun resolvePartialDate(
    raw: String,
    format: String,
    precision: DatePrecision,
    lastDay: Boolean,
    timeZone: ModelTimeZone,
): String? {
    val (y, storedMonth, storedDay) = partialDateParts(raw, format, precision, timeZone) ?: return null
    if (y == 0) return null
    val month = if (storedMonth != 0) storedMonth else if (lastDay) 12 else 1
    val day = if (storedDay != 0) storedDay else if (lastDay) daysInMonth(y, month) else 1
    return iso(y, month, day)
}

/** Source-compatible DAY_OPTIONAL overload retained for consumers of the original helper. */
fun resolvePartialDate(raw: String, format: String, lastDay: Boolean, timeZone: ModelTimeZone): String? =
    resolvePartialDate(raw, format, DatePrecision.DAY_OPTIONAL, lastDay, timeZone)

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
    timeZone: ModelTimeZone,
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
    if (wrapsAcrossTheYear(startValues, endValues, comps, timeZone)) {
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
    val start = buildRangeEndpoint(startValues, comps, startYear, end = false, timeZone) ?: return null
    val end = buildRangeEndpoint(endValues, comps, endYear, end = completeEndToLastDay, timeZone) ?: return null
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
private fun wrapsAcrossTheYear(
    startValues: List<String>,
    endValues: List<String>,
    comps: List<Char>,
    timeZone: ModelTimeZone,
): Boolean {
    if ('y' in comps || 'd' !in comps || 'M' !in comps) return false
    val start = buildRangeEndpoint(startValues, comps, LEAP_PROBE_YEAR, end = false, timeZone) ?: return false
    val end = buildRangeEndpoint(endValues, comps, LEAP_PROBE_YEAR, end = false, timeZone) ?: return false
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
 * end on that month's real last day (proleptic, so February 1500 ends on the 28th). The completed endpoint is
 * parsed like any date ([resolveParsedDate]).
 */
private fun buildRangeEndpoint(
    values: List<String>,
    comps: List<Char>,
    baseYear: Int,
    end: Boolean,
    timeZone: ModelTimeZone,
): String? {
    var y = baseYear; var mo = -1; var d = -1
    comps.forEachIndexed { i, c -> val v = values[i].toInt(); when (c) { 'y' -> y = v; 'M' -> mo = v; 'd' -> d = v } }
    if (mo == -1) mo = if (end) 12 else 1                          // missing month → interval bound
    if (mo < 1 || mo > 12) return null                            // a PRESENT month must be real
    if (d == -1) d = if (end) daysInMonth(y, mo) else 1           // missing day → interval bound
    return resolveParsedDate(y, mo, d, timeZone)?.let { (ry, rm, rd) -> iso(ry, rm, rd) }
}

/**
 * Parse a single date value rendered in [format] (the kernel's `DateType.format`, e.g. `dd.MM.yyyy`,
 * `MM/dd/yyyy`, or ISO `yyyy-MM-dd`) into the canonical ISO `yyyy-MM-dd`, or null when it doesn't match the
 * format (a malformed date — the kernel's `datumFormatFalsch`). The interpreter stores every date as ISO so
 * comparison stays lexicographic-chronological and the extractors parse directly; this is how a non-ISO
 * field's rendered value is brought to that canonical form (locale-variant by reading the declared format).
 */
fun parseDateToIso(raw: String, format: String, timeZone: ModelTimeZone): String? {
    val pattern = dateFragment(format) ?: return null
    return buildIso(pattern.capture(raw) ?: return null, pattern.components, timeZone)
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
            val width = j - i
            // A12 31.1.1 renders with `DateTimeFormatter`, whose year of four or more letters is EXCEEDS_PAD: a year
            // wider than the run carries a `+` (`10000` → `+10000`), where `SimpleDateFormat` printed it bare.
            if (c == 'y' && width >= 4 && v.toString().length > width) sb.append('+')
            sb.append(v.toString().padStart(width, '0'))
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
 *
 * The result is the wall clock of the instant A12 parses the value to, so a Europe/Berlin wall time inside a
 * spring-forward gap comes back moved forward by the gap (`2024-03-31T02:30:00` → `03:30:00`), the way
 * `ZonedDateTime` resolves it, and a Julian leap day takes [resolveParsedDate]'s reading.
 */
fun parseDateTimeToIso(raw: String, format: String, timeZone: ModelTimeZone): String? {
    val pattern = dateTimeFragment(format) ?: return null
    return buildIsoDateTime(pattern.capture(raw) ?: return null, pattern.components, timeZone)
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
fun parseDeclaredDateTime(raw: String, format: String, timeZone: ModelTimeZone): String? =
    parseDateTimeToIso(raw, format, timeZone) ?: parseTimeToIso(raw, format)

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
 * `'…'` as a literal whose quotes are delimiters — so a `yyyy-MM-dd'T'HH:mm:ss` format matches a rendered
 * `2024-06-01T12:30:00` (no quotes in the value). A doubled `''` contributes nothing, where SimpleDateFormat
 * would read a literal quote; that difference is unreachable, because A12 admits no format carrying `''` and
 * its only quoted literal is ISO's `'T'` ([TEMPORAL-FORMAT-VOCABULARY](../../../../../../../../../../../docs/TEMPORAL-FORMAT-VOCABULARY.md)).
 * Null if [format] carries no date/time component.
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
            c == '\'' -> {                                   // a quoted literal section: 'T' → literal T, '' → nothing
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
private fun buildIsoDateTime(values: List<String>, comps: List<Char>, timeZone: ModelTimeZone): String? {
    var y = -1; var mo = -1; var d = -1; var h = 0; var mi = 0; var s = 0
    comps.forEachIndexed { idx, c ->
        val v = values[idx].toInt()
        when (c) { 'y' -> y = v; 'M' -> mo = v; 'd' -> d = v; 'H' -> h = v; 'm' -> mi = v; 's' -> s = v }
    }
    if (h > 23 || mi > 59 || s > 59) return null
    val (ry, rm, rd) = resolveParsedDate(y, mo, d, timeZone) ?: return null
    val local = epochDayOfCivil(ry, rm, rd) * 86_400L + h * 3_600L + mi * 60L + s
    return isoFromZonedInstant(localToInstant(local, timeZone), timeZone)
}

internal fun twoDigits(n: Int): String = n.toString().padStart(2, '0')

/** Assemble ISO `yyyy-MM-dd` from a date fragment's captured component [values] in [comps] order; null if out of range. */
private fun buildIso(values: List<String>, comps: List<Char>, timeZone: ModelTimeZone): String? {
    var y = -1; var mo = -1; var d = -1
    comps.forEachIndexed { idx, c -> when (c) { 'y' -> y = values[idx].toInt(); 'M' -> mo = values[idx].toInt(); 'd' -> d = values[idx].toInt() } }
    return resolveParsedDate(y, mo, d, timeZone)?.let { (ry, rm, rd) -> iso(ry, rm, rd) }
}

/** Whether [s] is a canonical ISO date-time (`yyyy-MM-dd'T'HH:mm:ss`) — the shape every resolved DATE_TIME
 *  value carries; the datetime-comparison dispatch keys on it. */
fun isIsoDateTime(s: String): Boolean = ISO_DATE_TIME.matches(s)

/** Whether [s] is a canonical ISO DATE (`yyyy-MM-dd`, no time part) — the shape every resolved DATE value
 *  carries; the date-comparison instant dispatch keys on it. */
fun isIsoDate(s: String): Boolean = ISO.matches(s)
