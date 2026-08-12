package io.github.mbackschat.a12.dm.adapter.perf

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [PerfEnvironmentQuality]'s produced shape — IG121's third piece. The committed-record guard
 * (`PerformanceRecordShapeGuardTest`) can only validate rows that already exist, and it lives in the root module
 * where this object is not visible, so the producer's contract is held here.
 *
 * The delta rules carry the weight rather than the raw counters: what a later reader needs is that an unobservable
 * counter is distinguishable from an observed zero, and that a counter which went backwards is never reported as a
 * negative "improvement".
 */
class PerfEnvironmentQualityTest {

    @Test
    fun aSnapshotAlwaysCarriesTheJvmsOwnCountersWhateverThePlatform() {
        val snapshot = PerfEnvironmentQuality.snapshot()

        assertTrue(snapshot.gcCount >= 0) { "GC count comes from a JVM bean and is always available" }
        assertTrue(snapshot.gcMs >= 0) { "GC time comes from a JVM bean and is always available" }
        // JIT monitoring is optional in the JVM spec, so this is present-or-null rather than asserted non-null.
        snapshot.jitMs?.let { assertTrue(it >= 0) { "a reported JIT total is never negative" } }
    }

    @Test
    fun theFirstWindowOfARunIsLabelledFromJvmStart() {
        val row = PerfEnvironmentQuality.delta(null, PerfEnvironmentQuality.snapshot())

        assertEquals("jvm-start", row["windowSince"]) {
            "the first observation has no predecessor, and a reader must not mistake its window for a " +
                "between-observations one — they measure different spans"
        }
        assertNotNull(row["gcCountInWindow"], "GC activity since JVM start is always measurable")
    }

    @Test
    fun alaterWindowIsLabelledFromItsPredecessor() {
        val first = PerfEnvironmentQuality.snapshot()

        val row = PerfEnvironmentQuality.delta(first, PerfEnvironmentQuality.snapshot())

        assertEquals("previous-observation", row["windowSince"])
    }

    /**
     * **A counter that went backwards is `null`, not a negative.** A bean reset or a rotated cgroup file produces
     * a smaller `after`, and reporting the difference would enter the record as a *negative* GC time — which reads
     * as an improvement rather than as the unusable observation it is.
     */
    @Test
    fun aCounterThatWentBackwardsIsReportedAsNullRatherThanNegative() {
        val high = PerfEnvironmentQuality.Snapshot(
            gcCount = 100, gcMs = 500, jitMs = 900,
            stealJiffies = 70, throttledUsec = 80, throttledPeriods = 90,
        )
        val low = high.copy(gcCount = 1, gcMs = 2, jitMs = 3, stealJiffies = 4, throttledUsec = 5, throttledPeriods = 6)

        val row = PerfEnvironmentQuality.delta(high, low)

        assertNull(row["gcCountInWindow"], "a backwards GC count is unusable, not a negative delta")
        assertNull(row["gcMsInWindow"], "a backwards GC time is unusable, not a negative delta")
        assertNull(row["jitMsInWindow"], "a backwards JIT total is unusable, not a negative delta")
        assertNull(row["stealJiffiesInWindow"])
        assertNull(row["cgroupThrottledUsecInWindow"])
        assertNull(row["cgroupThrottledPeriodsInWindow"])
    }

    /**
     * **An unobservable counter stays `null`; it does not become 0.** This is the absence-versus-zero distinction
     * [IF285] was reopened over: a 0 reads as "measured, none seen", which is a claim nobody made on a platform
     * that cannot report the counter at all.
     */
    @Test
    fun anUnobservableCounterStaysNullInsteadOfBecomingZero() {
        val before = PerfEnvironmentQuality.Snapshot(
            gcCount = 1, gcMs = 1, jitMs = null, stealJiffies = null, throttledUsec = null, throttledPeriods = null,
        )
        val after = before.copy(gcCount = 3, gcMs = 9)

        val row = PerfEnvironmentQuality.delta(before, after)

        assertNull(row["jitMsInWindow"], "JIT monitoring unavailable is not 'no JIT work happened'")
        assertNull(row["stealJiffiesInWindow"], "steal time is Linux-only; on macOS it is unknown, not zero")
        assertNull(row["cgroupThrottledUsecInWindow"], "cgroup throttling is absent outside a container, not zero")
        assertEquals(2L, row["gcCountInWindow"], "…while the counters that ARE observable still report their delta")
        assertEquals(8L, row["gcMsInWindow"])
    }

    /** The exact key set an observation row merges, so a rename cannot pass the record guard's tolerant branch. */
    @Test
    fun theProducedKeySetIsExactlyWhatTheRecordDocuments() {
        val row = PerfEnvironmentQuality.delta(null, PerfEnvironmentQuality.snapshot())

        assertEquals(
            setOf(
                "windowSince", "gcCountInWindow", "gcMsInWindow", "jitMsInWindow",
                "stealJiffiesInWindow", "cgroupThrottledUsecInWindow", "cgroupThrottledPeriodsInWindow",
            ),
            row.keys,
            "perf-results/README.md and PerformanceRecordShapeGuardTest both name these; a rename here must " +
                "fail rather than silently write a field no consumer reads",
        )
    }
}
