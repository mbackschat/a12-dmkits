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
 */
class GrowthFloorRetryTest {

    @Test
    fun aCleanFirstEstimateCostsExactlyOneMeasurement() {
        val ratios = scripted(4.0, 99.0, 99.0)
        PerfHarness.assertGrowthBelowCatastropheFloor("clean", floor = 9.0, estimate = ratios.next)
        assertEquals(1, ratios.taken, "a passing guard must not pay for re-measurements it does not need")
    }

    @Test
    fun oneLowOutlierCannotClearABreach() {
        val ratios = scripted(10.0, 10.0, 8.0)
        val failure = assertThrows(AssertionError::class.java) {
            PerfHarness.assertGrowthBelowCatastropheFloor("breach", floor = 9.0, estimate = ratios.next)
        }
        assertEquals(3, ratios.taken, "a breaching first estimate must be re-measured to the full attempt count")
        assertTrue("median" in failure.message.orEmpty()) {
            "the failure must say acceptance is the median, since that is what makes it sound: ${failure.message}"
        }
    }

    @Test
    fun aSingleLoadSpikeIsAbsorbed() {
        val ratios = scripted(9.5, 4.0, 4.1)
        PerfHarness.assertGrowthBelowCatastropheFloor("spike", floor = 9.0, estimate = ratios.next)
        assertEquals(3, ratios.taken)
    }

    @Test
    fun aGenuineBlowupFailsOnEveryAttempt() {
        val ratios = scripted(16.0, 16.2, 15.8)
        val failure = assertThrows(AssertionError::class.java) {
            PerfHarness.assertGrowthBelowCatastropheFloor(
                "blowup", culprit = "the widget cache", floor = 9.0, estimate = ratios.next,
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
            PerfHarness.assertGrowthBelowCatastropheFloor("even", attempts = 2) { growth(4.0) }
        }
        assertTrue("odd" in failure.message.orEmpty()) { failure.message.orEmpty() }
    }

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
