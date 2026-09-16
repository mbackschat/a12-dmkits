package io.github.mbackschat.a12.dm.adapter.perf

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * **The decision rule of [PerfHarness.assertReuseRatioBelowCeiling], on scripted numbers.**
 *
 * Its two siblings each needed this class for the same reason, and the cross-engine one needed it *retroactively*
 * after a cold review neutered its ceiling and a 3m17s lane stayed green. A timing lane cannot supply the
 * coverage: it measures real hardware, so it cannot script a breach, and removing a ceiling can only ever make a
 * timing lane greener. The rule has to be checked without a clock.
 *
 * The reuse ceiling has one failure mode its siblings do not, and [aRatioExactlyAtTheCeilingBreaches] pins it: the
 * ceiling this seam is built for is `1.0`, the value a reuse ratio sits at when the reuse saves *nothing*, so a
 * `<=` written where `<` belongs would admit precisely the case the bar exists to refuse.
 */
class ReuseCeilingRetryTest {

    private val ceiling = 1.0

    @Test
    fun aCleanFirstEstimateCostsExactlyOneMeasurement() {
        val estimates = scripted(0.5, 9.0, 9.0)
        PerfHarness.assertReuseRatioBelowCeiling("clean", ceiling, estimate = estimates.next)
        assertEquals(1, estimates.taken, "a passing bar must not pay for re-measurements it does not need")
    }

    @Test
    fun oneLowOutlierCannotClearABreach() {
        val estimates = scripted(1.4, 1.5, 0.4)
        val failure = assertThrows(AssertionError::class.java) {
            PerfHarness.assertReuseRatioBelowCeiling("breach", ceiling, estimate = estimates.next)
        }
        assertEquals(3, estimates.taken, "a breach must be re-measured to the full attempt count")
        assertTrue(failure.message!!.contains("across 3 independent re-measurements")) {
            "the failure must say the breach survived re-measurement: ${failure.message}"
        }
    }

    @Test
    fun aSingleLoadSpikeIsAbsorbed() {
        val estimates = scripted(1.4, 0.5, 0.6)
        PerfHarness.assertReuseRatioBelowCeiling("spike", ceiling, estimate = estimates.next)
        assertEquals(3, estimates.taken)
    }

    /** A reuse that saves exactly nothing is the case the bar exists to catch, so the comparison must be strict. */
    @Test
    fun aRatioExactlyAtTheCeilingBreaches() {
        val estimates = scripted(1.0, 1.0, 1.0)
        assertThrows(AssertionError::class.java) {
            PerfHarness.assertReuseRatioBelowCeiling("no saving at all", ceiling, estimate = estimates.next)
        }
    }

    @Test
    fun theFailureNamesBothArmsOfEveryAttempt() {
        val estimates = scripted(1.4, 1.5, 1.6)
        val failure = assertThrows(AssertionError::class.java) {
            PerfHarness.assertReuseRatioBelowCeiling("named", ceiling, estimate = estimates.next)
        }
        assertTrue(listOf("1.4", "1.5", "1.6").all { it in failure.message.orEmpty() }) {
            "every attempt must be named so a real regression is unmistakable: ${failure.message}"
        }
        assertTrue("stateless" in failure.message.orEmpty() && "stateful" in failure.message.orEmpty()) {
            "a ratio alone does not say which arm moved, so both must appear: ${failure.message}"
        }
    }

    @Test
    fun anEvenAttemptCountHasNoMedianAndIsRejected() {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            PerfHarness.assertReuseRatioBelowCeiling("even", ceiling, attempts = 2) { Reuse(0.5, 2.0, 1.0) }
        }
        assertTrue("odd" in failure.message.orEmpty()) { failure.message.orEmpty() }
    }

    /** A fixed sequence of ratios that also records how many were consumed. */
    private fun scripted(vararg ratios: Double) = object {
        var taken = 0
            private set
        val next: () -> Reuse = {
            // The arms are back-derived from the ratio so a scripted case reads as one number: the stateless arm
            // is pinned and the stateful one carries the ratio.
            Reuse(ratios[taken], statelessMs = 1.0, statefulMs = ratios[taken]).also { taken++ }
        }
    }
}
