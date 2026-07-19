package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.epochSecond
import io.github.mbackschat.a12.dm.interpreter.eval.isGapLocal
import io.github.mbackschat.a12.dm.interpreter.eval.zonedInstantOf
import io.github.mbackschat.a12.dm.interpreter.model.ModelTimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Multiplatform (JVM + JS) lock for Europe/Berlin's **complete history** (IG83, Route B) — the pinned,
 * versioned transition table replacing the 1996+-only EU rule. Every offset below is the kernel's own path,
 * `java.util.TimeZone.getOffset` via `SimpleDateFormat` (JDK 21 tzdb 2026a; KERNEL-FINDINGS §6 supplement,
 * probe-derived) — NOT `java.time`, which is why the pre-1916 LMT is absent (the kernel sees flat CET) and
 * why the 1945/47 double-summer offset CEMT (+03:00) exists. The kernel differential pins these against the
 * real kernel in `adapter.laws.DstTimeZoneDiffTest`; this proves the kernel-free build reproduces them.
 *
 * The offset applied to a (non-gap) wall time is `wall − instant`, so [off] reads it straight off the zone math.
 */
class BerlinHistoricalTzTest {

    private fun off(wall: String): Long {
        val w = epochSecond(wall) ?: error("malformed $wall")
        val inst = zonedInstantOf(wall, ModelTimeZone.EUROPE_BERLIN) ?: error("unexpected gap at $wall")
        return w - inst
    }

    // ---- pre-1916: the kernel path DROPS the LMT → flat CET (+01:00), never CEST or the 3208 s LMT ----------
    @Test fun before1916IsFlatCet() {
        assertEquals(3600L, off("1583-10-16T12:00:00"), "the Gregorian-start floor is CET, not LMT")
        assertEquals(3600L, off("1700-01-01T12:00:00"), "pre-standardization Berlin is flat CET to java.util")
        assertEquals(3600L, off("1850-06-01T12:00:00"), "summer 1850 is still CET — there was no DST yet")
    }

    // ---- the 1945 double-summer window: CEMT (+03:00), an offset the modern rule never produces -------------
    @Test fun midsummer1945IsCemt() =
        assertEquals(10800L, off("1945-06-01T12:00:00"), "1945-05-24 → 09-24 Berlin ran on CEMT +03:00")

    @Test fun the1945FallBackOverlapResolvesToTheSmallerOffset() =
        assertEquals(7200L, off("1945-09-24T02:30:00"), "the CEMT→CEST overlap parses as the after-offset CEST, not CEMT")

    @Test fun the1945SpringGapIsRejected() =
        assertTrue(isGapLocal("1945-05-24T02:30:00", ModelTimeZone.EUROPE_BERLIN),
            "the CEST→CEMT spring-forward hour does not exist (a formal error), like any gap")

    // ---- 1980–1995: DST ends the last Sunday of SEPTEMBER, so October is already CET (the modern rule says CEST)
    @Test fun october1980IsCetBecauseDstEndedInSeptember() {
        assertEquals(7200L, off("1980-06-15T12:00:00"), "summer 1980 is CEST (control)")
        assertEquals(3600L, off("1980-10-15T12:00:00"), "DST ended 1980-09-28, so mid-October is already CET")
    }

    // ---- modern + future: the recurring EU rule still governs 1996+ (incl. beyond the materialized table) ----
    @Test fun modernAndFutureStillFollowTheEuRule() {
        assertEquals(3600L, off("2024-01-01T12:00:00"), "winter 2024 is CET")
        assertEquals(7200L, off("2024-06-01T12:00:00"), "summer 2024 is CEST")
        assertEquals(3600L, off("2037-01-01T12:00:00"), "a far-future winter beyond the table stays CET")
        assertEquals(7200L, off("2037-06-01T12:00:00"), "a far-future summer beyond the table stays CEST")
    }
}
