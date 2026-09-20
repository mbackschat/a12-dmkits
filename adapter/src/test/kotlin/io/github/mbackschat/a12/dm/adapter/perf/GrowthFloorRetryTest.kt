package io.github.mbackschat.a12.dm.adapter.perf

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The acceptance rule of [PerfHarness.assertGrowthBelowCatastropheFloor], on scripted estimates rather than a
 * clock — so the guard that every timing floor now depends on is itself checked, and cheaply.
 *
 * The rule an external review falsified was "return as soon as any attempt is below the floor": retrying then
 * makes a real breach *more* likely to be admitted the more attempts it gets. [oneLowOutlierCannotClearABreach]
 * is that exact sequence and is the reason this class exists; the others keep the fix from over-correcting into
 * a guard that fails on load or costs three estimates when the first one is clean.
 *
 * Every case here pins [TimingEnforcement] explicitly. The acceptance RULE and the question of what a breach
 * MEANS where it happened are separate, and leaving the second ambient would make these results depend on which
 * lane ran them.
 */
class GrowthFloorRetryTest {

    @Test
    fun aCleanFirstEstimateCostsExactlyOneMeasurement() {
        val ratios = scripted(4.0, 99.0, 99.0)
        gated("clean", ratios.next)
        assertEquals(1, ratios.taken, "a passing guard must not pay for re-measurements it does not need")
    }

    @Test
    fun oneLowOutlierCannotClearABreach() {
        val ratios = scripted(10.0, 10.0, 8.0)
        val failure = assertThrows(AssertionError::class.java) { gated("breach", ratios.next) }
        assertEquals(3, ratios.taken, "a breaching first estimate must be re-measured to the full attempt count")
        assertTrue("median" in failure.message.orEmpty()) {
            "the failure must say acceptance is the median, since that is what makes it sound: ${failure.message}"
        }
    }

    @Test
    fun aSingleLoadSpikeIsAbsorbed() {
        val ratios = scripted(9.5, 4.0, 4.1)
        gated("spike", ratios.next)
        assertEquals(3, ratios.taken)
    }

    @Test
    fun aGenuineBlowupFailsOnEveryAttempt() {
        val ratios = scripted(16.0, 16.2, 15.8)
        val failure = assertThrows(AssertionError::class.java) {
            PerfHarness.assertGrowthBelowCatastropheFloor(
                "blowup", culprit = "the widget cache", floor = 9.0,
                enforcement = TimingEnforcement.GATED, estimate = ratios.next,
            )
        }
        assertTrue("the widget cache" in failure.message.orEmpty()) {
            "the per-site culprit must survive into the failure: ${failure.message}"
        }
        assertTrue(listOf("16.0", "16.2", "15.8").all { it in failure.message.orEmpty() }) {
            "every attempt must be named so a real regression is unmistakable: ${failure.message}"
        }
    }

    @Test
    fun anEvenAttemptCountHasNoMedianAndIsRejected() {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            PerfHarness.assertGrowthBelowCatastropheFloor(
                "even", attempts = 2, enforcement = TimingEnforcement.GATED,
            ) { growth(4.0) }
        }
        assertTrue("odd" in failure.message.orEmpty()) { failure.message.orEmpty() }
    }

    /**
     * The nightly's requirement, as a test: on shared hosted hardware a breach of this self-referential floor is
     * REPORTED, never failed. Three hosted runs put the closest lane at 89 % of the floor and made the same commit
     * pass one night and fail the next two, which is a false positive by construction.
     */
    @Test
    fun aBreachOnSharedHardwareIsReportedRatherThanFailed() {
        val ratios = scripted(16.0, 16.2, 15.8)

        val accepted = PerfHarness.assertGrowthBelowCatastropheFloor(
            "hosted", floor = 9.0, enforcement = TimingEnforcement.FLAGGED, estimate = ratios.next,
        )

        assertEquals(16.0, accepted.ratio, "the flagged path must still return the ACCEPTED estimate — the median "
            + "of the attempts — so the caller anchors the numbers acceptance was decided on")
        assertEquals(3, ratios.taken, "a flag is not a shortcut: the breach is still re-measured before it is "
            + "reported, or the report would carry a single contended sample")
    }

    /**
     * The third branch, which `forGrowthFloor` never selects — an absolute anchor does, and the floor's `when`
     * must stay exhaustive over the enum rather than falling through to a throw if that ever changes.
     */
    @Test
    fun theUnjudgedBranchReportsWithoutFailing() {
        val ratios = scripted(16.0, 16.2, 15.8)

        val accepted = PerfHarness.assertGrowthBelowCatastropheFloor(
            "parallel forks", floor = 9.0, enforcement = TimingEnforcement.UNJUDGED, estimate = ratios.next,
        )

        assertEquals(16.0, accepted.ratio)
    }

    /**
     * The two kinds of self-referential bar have DIFFERENT preconditions, and collapsing them into one rule
     * switches guards off. A growth ratio is taken on the current-thread CPU clock, which already filters a
     * sibling fork preempting the JVM, so requiring the isolated lane for it would silently disarm
     * `CharsetPerfTest` and `CustomTypePerfTest` — the only timing bars an ordinary push runs.
     */
    @Test
    fun aGrowthFloorNeedsOnlyADedicatedMachineWhileAnAbsoluteAnchorAlsoNeedsTheIsolatedLane() {
        assertEquals(TimingEnforcement.GATED, TimingEnforcement.forGrowthFloor(sharedMachine = false))
        assertEquals(TimingEnforcement.FLAGGED, TimingEnforcement.forGrowthFloor(sharedMachine = true))

        assertEquals(TimingEnforcement.GATED,
            TimingEnforcement.forAbsoluteAnchor(isolatedLane = true, sharedMachine = false, foreignDemand = null))
        assertEquals(TimingEnforcement.FLAGGED,
            TimingEnforcement.forAbsoluteAnchor(isolatedLane = true, sharedMachine = true, foreignDemand = null))
        assertEquals(TimingEnforcement.UNJUDGED,
            TimingEnforcement.forAbsoluteAnchor(isolatedLane = false, sharedMachine = false, foreignDemand = null),
            "no clock filters a parallel-fork slice out of an ABSOLUTE time, so it cannot be judged there")
        assertEquals(TimingEnforcement.UNJUDGED,
            TimingEnforcement.forAbsoluteAnchor(isolatedLane = false, sharedMachine = true, foreignDemand = null))
    }

    private fun gated(label: String, estimate: () -> Growth) =
        PerfHarness.assertGrowthBelowCatastropheFloor(
            label, floor = 9.0, enforcement = TimingEnforcement.GATED, estimate = estimate,
        )

    private fun growth(ratio: Double) = Growth(ratio, 1.0, ratio)

    /** A fixed sequence of ratios that also records how many were consumed. */
    private fun scripted(vararg ratios: Double) = object {
        var taken = 0
            private set
        val next: () -> Growth = {
            growth(ratios[taken]).also { taken++ }
        }
    }
}
