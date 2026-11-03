package io.github.mbackschat.a12.dm.interpreter.eval

import io.github.mbackschat.a12.dm.interpreter.model.ModelTimeZone

// ---- model-timezone instant math — the Europe/Berlin axis (IF155 → KERNEL-FINDINGS §6) ------------------
//
// Kernel 31.1.1 holds every DATE/DATE_TIME as an instant and reads it as a `java.time.ZonedDateTime` in the model
// zone: parsing, construction, shifts, differences and part extraction all go through `ZonedDateTime`
// (`BedingungsOperatorHelper`, `DateUtil.createDate`, `ValidationDateParser`, source-read at a12-kernel eec9134;
// KERNEL-FINDINGS KF665). The interpreter keeps a canonical LOCAL ISO label and converts to instants with the same
// rules, clean-room: the proleptic ISO calendar, Berlin's tzdb history INCLUDING its local mean time before
// 1893-04-01 (java.time keeps it, where the 30.8.1 `java.util` path dropped it), and `ZonedDateTime.ofLocal`
// resolution — an overlap takes the EARLIER offset unless a preferred offset is valid, and a gap moves the wall
// time forward by the gap's length. UTC is the identity on every function here.

/** Epoch day of civil `year-month-day` — Hinnant days_from_civil as PURE integer arithmetic (no string build
 *  or regex, unlike [epochDay]); the offset path calls this per datetime value, so it must not allocate. */
internal fun epochDayOfCivil(year: Int, month: Int, day: Int): Long {
    val yAdj = if (month <= 2) year - 1L else year.toLong()
    val era = (if (yAdj >= 0) yAdj else yAdj - 399) / 400
    val yoe = yAdj - era * 400
    val doy = (153L * (if (month > 2) month - 3 else month + 9) + 2) / 5 + day - 1
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
    return era * 146097 + doe - 719468
}

/** The civil YEAR of an epoch [day], read off [civilOfEpochDay] rather than parsed back out of the string
 *  [isoFromEpochDay] builds; the offset path calls this per datetime value. */
internal fun yearOfEpochDay(day: Long): Int = civilOfEpochDay(day).year

/** A proleptic civil date. */
internal data class Civil(val year: Int, val month: Int, val day: Int)

/** The proleptic civil date of an epoch [day] (Hinnant civil_from_days). */
internal fun civilOfEpochDay(day: Long): Civil {
    val zz = day + 719468
    val era = (if (zz >= 0) zz else zz - 146096) / 146097
    val doe = zz - era * 146097
    val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
    val y = yoe + era * 400
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val d = doy - (153 * mp + 2) / 5 + 1
    val mo = if (mp < 10) mp + 3 else mp - 9
    return Civil((if (mo <= 2) y + 1 else y).toInt(), mo.toInt(), d.toInt())
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

/** Berlin's local mean time, +00:53:28 — tzdb's offset before 1893-04-01, which `java.time` applies. */
private const val BERLIN_LMT = 3_208L

/** The instant Berlin left local mean time: 1893-04-01T00:00 LMT, when its clocks moved to CET 00:06:32. */
private val BERLIN_LMT_END = epochDayOfCivil(1893, 4, 1) * 86_400L - BERLIN_LMT

/** Every UTC offset (seconds) Berlin has used — LMT, CET, CEST and the 1945/47 CEMT — ASCENDING. A wall time has
 *  at most one instant per offset; [localToInstant] tries each. */
private val BERLIN_OFFSETS = longArrayOf(BERLIN_LMT, 3_600L, 7_200L, 10_800L)

// Berlin's transitions 1916→1997, pinned to JDK 21 tzdb 2026a. Each epoch-second in [BERLIN_TX_EPOCH] is when the
// offset in the aligned slot of [BERLIN_TX_OFFSET] takes effect; between [BERLIN_LMT_END] and the first entry
// Berlin is flat CET, and at/after the last entry the recurring EU rule governs. Ascending, so
// [berlinOffsetSeconds] binary-searches.
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

/** Berlin's UTC offset in seconds at [instant] over its COMPLETE history — local mean time before 1893-04-01,
 *  flat CET to 1916, the pinned [BERLIN_TX_EPOCH]/[BERLIN_TX_OFFSET] transitions through 1997 (CET/CEST/CEMT),
 *  and the recurring EU rule beyond. Binary search + pure-integer civil math — no per-call allocation — because
 *  this is on the hot path for every conversion under a Berlin model. */
private fun berlinOffsetSeconds(instant: Long): Long {
    if (instant < BERLIN_LMT_END) return BERLIN_LMT
    if (instant < BERLIN_TX_EPOCH[0]) return 3_600L
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

/** [tz]'s UTC offset in seconds at [instant]. */
internal fun offsetSeconds(instant: Long, tz: ModelTimeZone): Long = when (tz) {
    ModelTimeZone.UTC -> 0L
    ModelTimeZone.EUROPE_BERLIN -> berlinOffsetSeconds(instant)
}

/**
 * The instant of a LOCAL wall time (naive epoch seconds, [epochSecond]) under [tz], resolved the way
 * `ZonedDateTime.ofLocal` resolves it — the rule behind every value A12 31.1.1 parses, constructs or shifts:
 *  - a wall time with exactly one valid offset takes it;
 *  - an **overlap** takes [preferredOffset] when that is one of its two valid offsets, else the **earlier** offset
 *    (the one in force before the transition, the larger) — probe-measured: 2024-10-27T02:30 to 03:00 is 90 minutes;
 *  - a **gap** moves the wall time forward by the gap's length, which is the instant at the pre-gap offset —
 *    probe-measured: 2024-03-31T02:30 and 03:30 are the same instant, and so are 1945-05-24T02:30 and 03:30.
 *
 * Total: every wall time has an instant (KERNEL-FINDINGS KF665).
 */
fun localToInstant(localSeconds: Long, tz: ModelTimeZone, preferredOffset: Long? = null): Long = when (tz) {
    ModelTimeZone.UTC -> localSeconds
    ModelTimeZone.EUROPE_BERLIN -> {
        var earliest = Long.MAX_VALUE
        var gapBeforeOffset = Long.MIN_VALUE
        var resolved: Long? = null
        for (offset in BERLIN_OFFSETS) {
            val instant = localSeconds - offset
            val actual = berlinOffsetSeconds(instant)
            if (actual == offset) {
                if (offset == preferredOffset) resolved = instant
                earliest = minOf(earliest, instant)
            } else if (actual > offset) {
                // In a gap every offset up to the pre-gap one lands past the transition; the largest such offset
                // IS the pre-gap offset, because any larger one lands before the transition, on that very offset.
                gapBeforeOffset = offset
            }
        }
        when {
            resolved != null -> resolved
            earliest != Long.MAX_VALUE -> earliest
            else -> {
                check(gapBeforeOffset != Long.MIN_VALUE) { "Berlin wall time $localSeconds has no bracketing offset" }
                localSeconds - gapBeforeOffset
            }
        }
    }
}

/** The LOCAL wall time (naive epoch seconds) of [instant] under [tz]. */
fun instantToLocal(instant: Long, tz: ModelTimeZone): Long = instant + offsetSeconds(instant, tz)

/** The instant of a canonical ISO date-time under [tz], or null when malformed. */
fun zonedInstantOf(iso: String, tz: ModelTimeZone): Long? = epochSecond(iso)?.let { localToInstant(it, tz) }

/** The canonical ISO date-time rendering of [instant]'s wall clock under [tz] — proleptic, as `ZonedDateTime` reads it. */
fun isoFromZonedInstant(instant: Long, tz: ModelTimeZone): String = isoFromEpochSecond(instantToLocal(instant, tz))

// ---- the kernel's ZonedDateTime operations, over instants in MILLISECONDS ---------------------------------
//
// A12 carries a `java.util.Date`, so an instant has millisecond precision (a clock reading keeps its remainder);
// every transition sits on a whole second, so the remainder rides along unchanged.

private const val MILLIS_PER_DAY = 86_400_000L

private fun localMillis(instantMillis: Long, tz: ModelTimeZone): Long =
    instantMillis + offsetSeconds(instantMillis.floorDiv(1_000L), tz) * 1_000L

private fun instantMillisOfLocal(localMillis: Long, tz: ModelTimeZone, preferredOffset: Long?): Long =
    localToInstant(localMillis.floorDiv(1_000L), tz, preferredOffset) * 1_000L + localMillis.mod(1_000L)

/** The proleptic civil date of [instantMillis]'s wall clock under [tz] — `ZonedDateTime.get` of the date fields. */
internal fun localCivil(instantMillis: Long, tz: ModelTimeZone): Civil =
    civilOfEpochDay(localMillis(instantMillis, tz).floorDiv(MILLIS_PER_DAY))

/** Midnight of the local date [epochDay] — `LocalDate.atStartOfDay(zone)`. Its gap rule (the first valid time after
 *  the gap) and [localToInstant]'s (forward by the gap length) agree for Berlin, whose only midnight gap starts at
 *  midnight (1893-04-01, LMT → CET). */
internal fun startOfDayMillis(epochDay: Long, tz: ModelTimeZone): Long =
    localToInstant(epochDay * 86_400L, tz) * 1_000L

/** `ZonedDateTime.plusDays`: the wall clock moves by whole days, keeping the source's offset where it stays valid. */
internal fun plusLocalDays(instantMillis: Long, days: Long, tz: ModelTimeZone): Long =
    instantMillisOfLocal(localMillis(instantMillis, tz) + days * MILLIS_PER_DAY, tz, sourceOffset(instantMillis, tz))

/** `ZonedDateTime.plusMonths`: the day clamps to the target month's length; time and preferred offset ride along. */
internal fun plusLocalMonths(instantMillis: Long, months: Long, tz: ModelTimeZone): Long? {
    val local = localMillis(instantMillis, tz)
    val civil = civilOfEpochDay(local.floorDiv(MILLIS_PER_DAY))
    val total = civil.year * 12L + (civil.month - 1) + months
    return civilLanding(total.floorDiv(12L), total.mod(12L).toInt() + 1, civil.day, local, instantMillis, tz)
}

/** `ZonedDateTime.plusYears`: a February 29 clamps to the 28th in a common year. */
internal fun plusLocalYears(instantMillis: Long, years: Long, tz: ModelTimeZone): Long? {
    val local = localMillis(instantMillis, tz)
    val civil = civilOfEpochDay(local.floorDiv(MILLIS_PER_DAY))
    return civilLanding(civil.year + years, civil.month, civil.day, local, instantMillis, tz)
}

/** `java.time`'s year range; beyond it a `ZonedDateTime` cannot exist. */
private const val MAX_YEAR = 999_999_999L

private fun civilLanding(year: Long, month: Int, day: Int, local: Long, source: Long, tz: ModelTimeZone): Long? {
    if (year !in -MAX_YEAR..MAX_YEAR) return null
    val y = year.toInt()
    val landingDay = epochDayOfCivil(y, month, minOf(day, daysInMonth(y, month)))
    return instantMillisOfLocal(landingDay * MILLIS_PER_DAY + local.mod(MILLIS_PER_DAY), tz, sourceOffset(source, tz))
}

private fun sourceOffset(instantMillis: Long, tz: ModelTimeZone): Long = offsetSeconds(instantMillis.floorDiv(1_000L), tz)

private fun Civil.isFebruary28(): Boolean = month == 2 && day == 28

/**
 * `AddYears` as A12 31.1.1 computes it: `plusYears`, then one correction on February 28 — a common-year source
 * landing on a leap year's 28th moves to the 29th, and a leap-year source landing on a common year's 28th is reset to
 * that day's start, dropping its time of day.
 */
internal fun shiftYears(instantMillis: Long, years: Long, tz: ModelTimeZone): Long? {
    val result = plusLocalYears(instantMillis, years, tz) ?: return null
    val source = localCivil(instantMillis, tz)
    val landed = localCivil(result, tz)
    if (!source.isFebruary28() || !landed.isFebruary28()) return result
    return when {
        !isGregorianLeapYear(source.year) && isGregorianLeapYear(landed.year) -> plusLocalDays(result, 1, tz)
        isGregorianLeapYear(source.year) && !isGregorianLeapYear(landed.year) ->
            startOfDayMillis(localMillis(result, tz).floorDiv(MILLIS_PER_DAY), tz)
        else -> result
    }
}

/** `AddMonths` as A12 31.1.1 computes it: `plusMonths`, then the leap-year February 28 time reset of [shiftYears]. */
internal fun shiftMonths(instantMillis: Long, months: Long, tz: ModelTimeZone): Long? {
    val result = plusLocalMonths(instantMillis, months, tz) ?: return null
    val source = localCivil(instantMillis, tz)
    val landed = localCivil(result, tz)
    return if (source.isFebruary28() && isGregorianLeapYear(source.year) &&
        landed.isFebruary28() && !isGregorianLeapYear(landed.year)) {
        startOfDayMillis(localMillis(result, tz).floorDiv(MILLIS_PER_DAY), tz)
    } else {
        result
    }
}

/**
 * Signed `DifferenceInDays(a, b)`: complete days between the two WALL CLOCKS, as `ChronoUnit.DAYS.between` counts two
 * `ZonedDateTime`s in one zone — the date difference, less one when the later time of day has not been reached. The
 * operands are ordered by instant first and the sign reapplied, as A12 does.
 */
internal fun differenceInDays(aMillis: Long, bMillis: Long, tz: ModelTimeZone): Long {
    val forward = aMillis < bMillis
    val first = localMillis(if (forward) aMillis else bMillis, tz)
    val second = localMillis(if (forward) bMillis else aMillis, tz)
    var days = second.floorDiv(MILLIS_PER_DAY) - first.floorDiv(MILLIS_PER_DAY)
    val time = second.mod(MILLIS_PER_DAY) - first.mod(MILLIS_PER_DAY)
    if (days > 0 && time < 0) days-- else if (days < 0 && time > 0) days++
    return if (forward) days else -days
}

/** Signed complete years: the year difference, less one when [shiftYears] of the earlier passes the later. */
internal fun differenceInYears(aMillis: Long, bMillis: Long, tz: ModelTimeZone): Long {
    val forward = aMillis < bMillis
    val first = if (forward) aMillis else bMillis
    val second = if (forward) bMillis else aMillis
    val years = positiveYears(first, second, tz)
    return if (forward) years else -years
}

/** Signed complete months: from the whole years, count months on a fresh copy of the earlier until one passes the later. */
internal fun differenceInMonths(aMillis: Long, bMillis: Long, tz: ModelTimeZone): Long {
    val forward = aMillis < bMillis
    val first = if (forward) aMillis else bMillis
    val second = if (forward) bMillis else aMillis
    var months = 12L * positiveYears(first, second, tz)
    while (checkNotNull(shiftMonths(first, months, tz)) <= second) months++
    return if (forward) months - 1 else -(months - 1)
}

private fun positiveYears(first: Long, second: Long, tz: ModelTimeZone): Long {
    val candidate = (localCivil(second, tz).year - localCivil(first, tz).year).toLong()
    return if (checkNotNull(shiftYears(first, candidate, tz)) > second) candidate - 1 else candidate
}
