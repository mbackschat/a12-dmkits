package io.github.mbackschat.a12.dm.interpreter.eval

import io.github.mbackschat.a12.dm.interpreter.model.ModelTimeZone

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

/** Canonical wall rendering of [instant] on A12's hybrid calendar (see [calendarDateParts]). */
internal fun isoFromCalendarInstant(instant: Long, tz: ModelTimeZone): String {
    val local = instantToLocal(instant, tz)
    val date = isoFromLegacyEpochDay(local.floorDiv(86_400L))
    val time = local.mod(86_400L)
    return "$date" + "T" + twoDigits((time / 3_600L).toInt()) + ":" +
        twoDigits(((time % 3_600L) / 60L).toInt()) + ":" + twoDigits((time % 60L).toInt())
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
internal fun differenceInCalendarDays(a: String, b: String, tz: ModelTimeZone): Int? {
    val aInstant = calendarOperandInstant(a, tz) ?: return null
    val bInstant = calendarOperandInstant(b, tz) ?: return null
    return differenceInCalendarDays(aInstant, bInstant, tz)
}

/** The instant of a canonical local DATE or DATE_TIME operand, its label read on A12's hybrid calendar. */
internal fun calendarOperandInstant(value: String, tz: ModelTimeZone): Long? {
    val local = if ('T' in value) {
        val date = value.substringBefore('T')
        val time = TIME_OF_DAY.matchEntire(value.substringAfter('T')) ?: return null
        val day = legacyEpochDay(date) ?: return null
        val hour = time.groupValues[1].toLong()
        val minute = time.groupValues[2].toLong()
        val second = time.groupValues[3].toLong()
        if (hour > 23L || minute > 59L || second > 59L) return null
        day * 86_400L + hour * 3_600L + minute * 60L + second
    } else {
        legacyEpochDay(value)?.times(86_400L)
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
 * label (SPEC-2026-07-29-01). Null when the label is not a real date on A12's hybrid calendar.
 */
internal fun fieldMutationInstantOfDate(isoDate: String, tz: ModelTimeZone): Long? {
    val day = legacyEpochDay(isoDate) ?: return null
    return fieldMutationLandingInstant(day * 86_400L, tz)
}

/**
 * The instant of a FRESHLY CONSTRUCTED / parsed DATE label — midnight resolved the way the kernel's
 * non-lenient zone parse resolves it ([localToInstant]): an overlap takes the smaller (after) offset, i.e.
 * the LATER copy. Retained on a constructed Date so a comparison against a shifted Date can observe that the
 * two selected different sides of one overlap. Null for a gap label (which the kernel rejects formally).
 */
internal fun freshDateInstant(isoDate: String, tz: ModelTimeZone): Long? {
    val day = legacyEpochDay(isoDate) ?: return null
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
