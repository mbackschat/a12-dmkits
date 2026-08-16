package io.github.mbackschat.a12.dm.adapter.perf

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * **The other side of the band: an observation far BELOW its anchor.**
 *
 * An anchor is only a bar for the revision it was captured on, and nothing in the estate could notice that it had
 * stopped being one. The 2026-08-12 refresh found `PF-framed` at `0.36×`/`0.39×` and `CO-correlation` at `0.43×` of
 * their recorded numbers after one optimization — ceilings sitting at up to `2.8×` the real cost, i.e. a bar that
 * could not fail — and it took a maintainer running a refresh by hand to see it. A scenario's fingerprint cannot
 * catch this: the scenario did not change, the code under it got faster.
 *
 * **Reported, never failed.** A legitimate optimization is the common cause, and failing the build until someone
 * refreshes the anchor would make a green run mean "nobody sped anything up lately". The upper bar keeps the
 * failure; this one keeps the notice.
 *
 * The threshold is measured, not chosen for roundness: with the `slackMs` term absorbing the sub-2 ms lanes, the
 * tightest fraction any observation reached across the three committed runs of each class is `0.933` locally and
 * `0.765` on the hosted runner, so `0.6` sits `1.27×` clear of the noisier of the two while still catching three of
 * the four real cases above (`0.64×` is inside the noise band and is deliberately not caught).
 */
class PerfStaleAnchorTest {

    private val key = ObservationKey(
        lane = "parallel-framed sub-quadratic",
        identityLabel = "diagnostic/PF-framed@1 fingerprint=6a346a",
        n = 20_000,
        engine = "interpreter",
        clock = "cpu",
    )

    private val tolerance = AnchorTolerance(factor = 1.8, slackMs = 1.0, staleFraction = 0.6)

    private fun budget(anchorMs: Double) =
        AnchorBudget(mapOf(key.text to anchorMs), unanchoredIsAFailure = true, tolerance = tolerance)

    /** The measured case: 18.86 ms anchored, 7.23 ms after the parallel-context optimization. */
    @Test
    fun anObservationFarBelowItsAnchorIsReportedAsStale() {
        val stale = budget(anchorMs = 18.86).staleReportFor(key, observedMs = 7.23)

        assertTrue(stale != null, "an anchor at 2.6× the real cost is a bar that cannot fail and must be noticed")
        assertTrue("0.38" in stale.orEmpty()) { "the report must state how far off the anchor is: $stale" }
        assertTrue("perf-anchor.sh" in stale.orEmpty()) { "…and how to fix it: $stale" }
    }

    @Test
    fun anObservationInsideTheMeasuredNoiseBandIsNotStale() {
        assertEquals(null, budget(anchorMs = 18.86).staleReportFor(key, observedMs = 17.0))
        assertEquals(null, budget(anchorMs = 18.86).staleReportFor(key, observedMs = 11.4),  // 0.60 × 18.86 − 1.0
            "the band's edge belongs to the honest side: a run this close is noise, not a stale anchor")
    }

    /**
     * The same absolute term the ceiling carries, for the same reason and on the same lanes. `PRA@500` anchors at
     * 2.21 ms on the hosted runner and one of the three runs measured 1.16 ms — a 0.52 ratio that is a millisecond
     * of jitter, not a 2× optimization. Without the slack this lane alone would force the fraction below 0.5.
     */
    @Test
    fun aTinyLaneIsNotCalledStaleOnAFractionOfAMillisecond() {
        val tiny = key.copy(lane = "row-invariant aggregate sub-quadratic", n = 500)
        val budget = AnchorBudget(mapOf(tiny.text to 2.21), unanchoredIsAFailure = true, tolerance = tolerance)

        assertEquals(null, budget.staleReportFor(tiny, observedMs = 1.16))
        assertTrue(budget.staleReportFor(tiny, observedMs = 0.2) != null,
            "a genuinely order-of-magnitude-faster tiny lane is still worth saying out loud")
    }

    /** A class with no block, or a key with no anchor, has nothing to be stale against. */
    @Test
    fun anUnanchoredObservationIsNotStale() {
        assertEquals(null, AnchorBudget(null, unanchoredIsAFailure = false, tolerance = tolerance)
            .staleReportFor(key, observedMs = 0.1))
        assertEquals(null, AnchorBudget(emptyMap(), unanchoredIsAFailure = false, tolerance = tolerance)
            .staleReportFor(key, observedMs = 0.1))
    }

    /** It rides the assertion path, so a real gate run prints it — the whole point is that nobody has to look. */
    @Test
    fun theReportIsPrintedByTheAssertionItself() {
        val printed = captureStdout {
            budget(anchorMs = 18.86).assertWithinBudget(key, 7.23, TimingEnforcement.GATED) { 7.23 }
        }

        assertTrue("[perf][STALE]" in printed) { "the gate must say it, not merely be able to: $printed" }
        assertFalse("[perf][STALE]" in captureStdout {
            budget(anchorMs = 18.86).assertWithinBudget(key, 17.0, TimingEnforcement.GATED) { 17.0 }
        })
    }

    /**
     * **A STALE claim needs a stable machine, so a hosted class does not make one.** Measured 2026-08-16: three
     * runs of ONE commit on one JDK, differing only in which CPU the provider handed out, read 7.92 / 15.09 /
     * 17.12 ms at n=1500 — a **2.16x spread on byte-identical code** (2.46x at n=6000). "Measured well below the
     * anchor" and "drew a fast CPU" are indistinguishable there, so the report asked for a refresh on 2 of 4
     * runs while the three-run median sat at 0.93x of the very anchor it called stale. The CPU lottery is
     * permanent — the three runs that authored the current block drew three different CPU models — so this is
     * not a gap awaiting better data.
     *
     * **What it costs, corrected 2026-08-16.** This first read "the within-run ratios keep the coverage because
     * they divide out the machine". They do divide out the machine, and that is exactly why they CANNOT keep this
     * coverage: a uniform speedup moves no ratio while the anchor drifts to a multiple of the real cost, which is
     * the one failure STALE exists to catch. The replacement is the STEP view of the committed record
     * (`scripts/perf-trend.sh`, run by the nightly), which compares a level across runs instead of one run
     * against a constant. It reports and exits 0, so on a hosted class this is a maintainer-read signal rather
     * than a gate — a downgrade, stated as one.
     */
    @Test
    fun aHostedClassMakesNoStaleClaimBecauseItCannotTellAFastCpuFromALooseAnchor() {
        val gated = captureStdout {
            budget(anchorMs = 18.86).assertWithinBudget(key, 7.23, TimingEnforcement.GATED) { 7.23 }
        }
        assertTrue("[perf][STALE]" in gated) { "an isolated machine still makes the claim: $gated" }

        val hosted = captureStdout {
            budget(anchorMs = 18.86).assertWithinBudget(key, 7.23, TimingEnforcement.FLAGGED) { 7.23 }
        }
        assertFalse("[perf][STALE]" in hosted) {
            "a hosted class must NOT claim staleness — the same observation is produced by a fast CPU draw: $hosted"
        }
    }

    @Test
    fun aStaleFractionThatWouldReportEveryHonestObservationIsRejected() {
        for (fraction in listOf(1.0, 1.5, 0.0, -0.1)) {
            val failure = runCatching { AnchorTolerance(factor = 1.8, slackMs = 1.0, staleFraction = fraction) }
            assertTrue(failure.isFailure) { "a stale fraction of $fraction must be refused" }
        }
    }

    private fun captureStdout(body: () -> Unit): String {
        val original = System.out
        val captured = java.io.ByteArrayOutputStream()
        System.setOut(java.io.PrintStream(captured, true))
        try {
            body()
        } finally {
            System.setOut(original)
        }
        return captured.toString()
    }
}
