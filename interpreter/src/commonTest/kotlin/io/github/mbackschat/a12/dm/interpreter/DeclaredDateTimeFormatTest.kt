package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.parseDateTimeToIso
import io.github.mbackschat.a12.dm.interpreter.eval.parseDeclaredDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pure-engine guard, JVM **and** JS, for the fact that a `DateTimeType` has **two** legal declared formats.
 *
 * A `DATE_TIME` field may be declared with the ISO `yyyy-MM-dd'T'HH:mm:ss` or with the degenerate **time-only**
 * `HH:mm:ss` (KERNEL-FINDINGS §6). Only the first was handled: [parseDateTimeToIso] requires all three calendar
 * components, so a time-only cell came back null, and BOTH the formal check and value resolution read that as
 * "malformed" — the cell was reported formally invalid *and* every referencing rule was suppressed (IF301).
 * The kernel-arbitrated half is `adapter.laws.TimeOnlyDateTimeDiffTest`; this is the portable half, and it also
 * pins the boundary that a naive fix would blur.
 */
class DeclaredDateTimeFormatTest {

    private val iso = "yyyy-MM-dd'T'HH:mm:ss"
    private val timeOnly = "HH:mm:ss"

    @Test fun theIsoFormatCanonicalizesToAnIsoDateTime() =
        assertEquals("2024-06-01T12:30:00", parseDeclaredDateTime("2024-06-01T12:30:00", iso))

    @Test fun theTimeOnlyFormatCanonicalizesToATime() =
        assertEquals("14:30:00", parseDeclaredDateTime("14:30:00", timeOnly))

    /**
     * The boundary. The fallback must be reachable ONLY from a date-less format, or a fix for the time-only
     * cell would start silently accepting a bare time in an ISO field — trading a false rejection for a false
     * acceptance, which is the worse direction.
     */
    @Test fun aBareTimeIsStillMalformedInAnIsoDeclaredField() =
        assertNull(parseDeclaredDateTime("14:30:00", iso))

    @Test fun anIsoValueIsStillMalformedInATimeOnlyDeclaredField() =
        assertNull(parseDeclaredDateTime("2024-06-01T12:30:00", timeOnly))

    @Test fun malformedValuesStayMalformedUnderBothFormats() {
        assertNull(parseDeclaredDateTime("25:00:00", timeOnly), "hour out of range")
        assertNull(parseDeclaredDateTime("14:61:00", timeOnly), "minute out of range")
        assertNull(parseDeclaredDateTime("14:30", timeOnly), "missing the seconds the format requires")
        assertNull(parseDeclaredDateTime("2024-13-01T12:30:00", iso), "month out of range")
        assertNull(parseDeclaredDateTime("", timeOnly), "empty")
    }

    /** The delegation is one-way: the date-bearing reading is tried first, so it still wins where it applies. */
    @Test fun theDateBearingReadingTakesPrecedence() =
        assertEquals(parseDateTimeToIso("2024-06-01T12:30:00", iso), parseDeclaredDateTime("2024-06-01T12:30:00", iso))
}
