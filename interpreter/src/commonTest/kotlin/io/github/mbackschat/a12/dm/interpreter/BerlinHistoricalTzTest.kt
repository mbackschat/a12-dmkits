package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.epochSecond
import io.github.mbackschat.a12.dm.interpreter.eval.berlinPinnedTransitionCount
import io.github.mbackschat.a12.dm.interpreter.eval.berlinPinnedTransitionEpoch
import io.github.mbackschat.a12.dm.interpreter.eval.berlinPinnedTransitionOffset
import io.github.mbackschat.a12.dm.interpreter.eval.instantToLocal
import io.github.mbackschat.a12.dm.interpreter.eval.isGapLocal
import io.github.mbackschat.a12.dm.interpreter.eval.localToInstant
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
 * real kernel in `adapter.laws.BerlinTransitionOracleDiffTest`; this proves the kernel-free build reproduces
 * the independently recorded vector at every boundary.
 *
 * The offset applied to a (non-gap) wall time is `wall − instant`, so [off] reads it straight off the zone math.
 */
class BerlinHistoricalTzTest {

    private data class Transition(val epoch: Long, val offset: Long)

    /**
     * Independently recorded from legacy java.util.TimeZone on OpenJDK 21.0.11, tzdb 2026a. This test vector
     * is deliberately not derived from DateMath's production arrays.
     */
    private val oracle = ORACLE_EPOCH.indices.map { Transition(ORACLE_EPOCH[it], ORACLE_OFFSET[it]) }

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

    @Test fun everyPinnedTransitionMatchesTheIndependentOracleAndBoundarySemantics() {
        val actual = (0 until berlinPinnedTransitionCount).map {
            Transition(berlinPinnedTransitionEpoch(it), berlinPinnedTransitionOffset(it))
        }
        assertEquals(oracle, actual, "JDK 21.0.11 / tzdb 2026a transition sequence")

        oracle.forEachIndexed { index, transition ->
            val beforeOffset = if (index == 0) 3_600L else oracle[index - 1].offset
            assertEquals(beforeOffset,
                instantToLocal(transition.epoch - 1, ModelTimeZone.EUROPE_BERLIN) - (transition.epoch - 1),
                "transition $index immediately before")
            assertEquals(transition.offset,
                instantToLocal(transition.epoch, ModelTimeZone.EUROPE_BERLIN) - transition.epoch,
                "transition $index at")
            assertEquals(transition.offset,
                instantToLocal(transition.epoch + 1, ModelTimeZone.EUROPE_BERLIN) - (transition.epoch + 1),
                "transition $index immediately after")

            val lowerLocal = transition.epoch + minOf(beforeOffset, transition.offset)
            val upperLocal = transition.epoch + maxOf(beforeOffset, transition.offset)
            val middleLocal = lowerLocal + (upperLocal - lowerLocal) / 2
            if (transition.offset > beforeOffset) {
                assertEquals(null, localToInstant(middleLocal, ModelTimeZone.EUROPE_BERLIN),
                    "transition $index forward jump is a gap")
            } else {
                assertEquals(middleLocal - transition.offset,
                    localToInstant(middleLocal, ModelTimeZone.EUROPE_BERLIN),
                    "transition $index backward jump resolves to the after/smaller offset")
            }
        }

        assertEquals(3_600L,
            instantToLocal(oracle.first().epoch - 86_400, ModelTimeZone.EUROPE_BERLIN)
                    - (oracle.first().epoch - 86_400),
            "pre-first behavior is flat CET")
        assertEquals(3_600L,
            instantToLocal(oracle.last().epoch + 1, ModelTimeZone.EUROPE_BERLIN) - (oracle.last().epoch + 1),
            "post-last behavior starts under the recurring EU winter offset")
    }

    @Test fun exactOracleGuardRejectsEveryStructuralMutationClass() {
        assertMutationRejected(oracle.toMutableList().also {
            it[7] = it[7].copy(epoch = it[7].epoch + 1)
        }, "one-second epoch mutation")
        assertMutationRejected(oracle.toMutableList().also {
            it[12] = it[12].copy(offset = 10_800L)
        }, "offset mutation")
        assertMutationRejected(oracle.drop(1), "missing pair")
        assertMutationRejected(oracle + Transition(oracle.last().epoch + 1, 7_200L), "extra pair")
        assertMutationRejected(oracle.toMutableList().also {
            val first = it[20]
            it[20] = it[21]
            it[21] = first
        }, "reordered pair")
    }

    private fun assertMutationRejected(candidate: List<Transition>, label: String) {
        assertTrue(candidate != oracle, "$label must fail the exact vector comparison")
    }

    private companion object {
        val ORACLE_EPOCH = longArrayOf(
            -1693706400L, -1680483600L, -1663455600L, -1650150000L, -1632006000L, -1618700400L,
            -938905200L, -857257200L, -844556400L, -828226800L, -812502000L, -796777200L,
            -781052400L, -776563200L, -765936000L, -761180400L, -748479600L, -733273200L,
            -717631200L, -714610800L, -710380800L, -701910000L, -684975600L, -670460400L,
            -654130800L, -639010800L, 323830800L, 338950800L, 354675600L, 370400400L,
            386125200L, 401850000L, 417574800L, 433299600L, 449024400L, 465354000L,
            481078800L, 496803600L, 512528400L, 528253200L, 543978000L, 559702800L,
            575427600L, 591152400L, 606877200L, 622602000L, 638326800L, 654656400L,
            670381200L, 686106000L, 701830800L, 717555600L, 733280400L, 749005200L,
            764730000L, 780454800L, 796179600L, 811904400L, 828234000L, 846378000L,
            859683600L, 877827600L,
        )
        val ORACLE_OFFSET = longArrayOf(
            7200L, 3600L, 7200L, 3600L, 7200L, 3600L,
            7200L, 3600L, 7200L, 3600L, 7200L, 3600L,
            7200L, 10800L, 7200L, 3600L, 7200L, 3600L,
            7200L, 10800L, 7200L, 3600L, 7200L, 3600L,
            7200L, 3600L, 7200L, 3600L, 7200L, 3600L,
            7200L, 3600L, 7200L, 3600L, 7200L, 3600L,
            7200L, 3600L, 7200L, 3600L, 7200L, 3600L,
            7200L, 3600L, 7200L, 3600L, 7200L, 3600L,
            7200L, 3600L, 7200L, 3600L, 7200L, 3600L,
            7200L, 3600L, 7200L, 3600L, 7200L, 3600L,
            7200L, 3600L,
        )
    }
}
