package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.FORMAL_RULE_PATH
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.DatePrecision
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Multiplatform (JVM + JS) lock for IF88 — the kernel's **always-on Gregorian-calendar date floor**: a date
 * VALUE that resolves to a real calendar date before `Constants.DATE_FROM = 1583-10-16` is the formal error
 * `datumFalsch` (FEHLER_DATUM), and a DATE_RANGE whose start is below the floor is `datumBereichDatumFalsch`.
 * Always applied (both kernel strategies), distinct from the opt-in "< 1900" additional check — so 1899 is
 * valid. The kernel differential (both strategies, compute path) is `adapter.laws.ComputedDateGregorianFloorDiffTest`.
 *
 * Boundary pinned by that differential: 1583-10-15 errors, 1583-10-16 is valid (strict `Date.before`).
 */
class DateGregorianFloorFormalTest {

    private fun code(field: FieldDef, value: String): String? {
        val m = ModelDef(listOf(field), emptyList())
        val doc = m.newDocument().group("/G", 1).field(field.path, value, 1, 1)
        return DmInterpreter(m).validateFull(doc).firstOrNull { it.rulePath == FORMAL_RULE_PATH }?.code
    }

    private val isoDate = FieldDef("/G/D", Kind.DATE, format = "yyyy-MM-dd")
    private val germanDate = FieldDef("/G/D", Kind.DATE, format = "dd.MM.yyyy")
    private val dayOptional = FieldDef("/G/D", Kind.DATE, format = "dd.MM.yyyy", datePrecision = DatePrecision.DAY_OPTIONAL)
    private val range = FieldDef("/G/D", Kind.DATE_RANGE, format = "yyyy-MM-dd", rangeSeparator = "/")

    // ── DATE: the floor at 1583-10-16 (datumFalsch), format-aware ──────────────────────────────────────────
    @Test fun ancientYearIsTooEarly() = assertEquals("datumFalsch", code(isoDate, "1000-01-31"), "year 1000 < floor")
    @Test fun dayBeforeFloorIsTooEarly() = assertEquals("datumFalsch", code(isoDate, "1583-10-15"), "1583-10-15 = floor − 1 day")
    @Test fun floorDayIsValid() = assertEquals(null, code(isoDate, "1583-10-16"), "1583-10-16 = the floor itself → valid")
    @Test fun dayAfterFloorIsValid() = assertEquals(null, code(isoDate, "1583-10-17"), "1583-10-17 → valid")
    // The floor is NOT the opt-in "< 1900" additional check — a plain date field accepts 1899.
    @Test fun year1899IsValid() = assertEquals(null, code(isoDate, "1899-12-31"), "1899 is above the Gregorian floor")
    @Test fun modernDateIsValid() = assertEquals(null, code(isoDate, "2024-06-15"), "a modern date → valid")
    // The floor does not shadow a genuine format/calendar error (a wrong month is still datumFormatFalsch).
    @Test fun malformedStaysFormatError() = assertEquals("datumFormatFalsch", code(isoDate, "2024-13-01"), "month 13 → format error")
    // Format-aware: the floor applies to the field's declared rendering format, not only ISO.
    @Test fun germanFormatTooEarly() = assertEquals("datumFalsch", code(germanDate, "15.10.1583"), "dd.MM.yyyy below floor")
    @Test fun germanFormatValid() = assertEquals(null, code(germanDate, "16.10.1583"), "dd.MM.yyyy at the floor → valid")

    // ── DAY_OPTIONAL: an omitted day completes to 01 BEFORE the floor check (the kernel's
    //    `replaceUnknownDateParts` runs ahead of `dateInvalid`), so a day-omitted October 1583 errors even
    //    though days 16–31 of that month are valid. Kernel-differential-pinned by the stored-value cases in
    //    `adapter.laws.ComputedDateGregorianFloorDiffTest`. ──────────────────────────────────────────────────
    @Test fun dayOptionalFloorMonthTooEarly() =
        assertEquals("datumFalsch", code(dayOptional, "00.10.1583"), "00.10.1583 completes to 01.10.1583 < floor")
    @Test fun dayOptionalNextMonthValid() =
        assertEquals(null, code(dayOptional, "00.11.1583"), "00.11.1583 completes to 01.11.1583 ≥ floor")

    // ── DATE_RANGE: the start-date floor (datumBereichDatumFalsch) ─────────────────────────────────────────
    @Test fun rangeStartTooEarly() =
        assertEquals("datumBereichDatumFalsch", code(range, "1000-01-01/2024-01-01"), "range start < floor")
    @Test fun rangeWithinBoundsValid() =
        assertEquals(null, code(range, "1600-01-01/2024-01-01"), "both sides above the floor → valid")
    // The floor does not shadow the start-after-end check.
    @Test fun rangeStartAfterEndStillInvalid() =
        assertEquals("datumBereichNichtGueltig", code(range, "2024-01-01/2020-01-01"), "start after end")
}
