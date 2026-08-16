package io.github.mbackschat.a12.dm.adapter.perf

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * **The decision rule of [PerfHarness.assertCrossEngineClassHolds], on scripted numbers.**
 *
 * Its sibling [PerfHarness.assertGrowthBelowCatastropheFloor] has carried `GrowthFloorRetryTest` since the
 * median-of-attempts contract was ratified, and that class calls the rule "the whole correctness of this helper".
 * The cross-engine helper duplicated the same contract and shipped with **nothing** — a cold review neutered the
 * ceiling (`if (true) return seen.single()`) and the entire `:adapter:test` lane stayed green for 3m17s, while
 * the identical mutation on the sibling dies in 18 s. This is that missing class.
 *
 * A timing lane cannot supply this: it measures real hardware, so it cannot script a breach, and removing a
 * ceiling can only ever make it greener. The rule has to be checked without a clock.
 */
class CrossEngineClassRetryTest {

    private val ceiling = 2.5

    @Test
    fun aCleanFirstEstimateCostsExactlyOneMeasurement() {
        val estimates = scripted(1.2, 99.0, 99.0)
        PerfHarness.assertCrossEngineClassHolds("clean", ceiling, estimate = estimates.next)
        assertEquals(1, estimates.taken, "a passing bar must not pay for re-measurements it does not need")
    }

    @Test
    fun oneLowOutlierCannotClearABreach() {
        val estimates = scripted(9.0, 9.0, 1.1)
        val failure = assertThrows(AssertionError::class.java) {
            PerfHarness.assertCrossEngineClassHolds("breach", ceiling, estimate = estimates.next)
        }
        assertEquals(3, estimates.taken, "a breach must be re-measured to the full attempt count")
        assertTrue(failure.message!!.contains("across 3 independent re-measurements"),
            "the failure must say the breach survived re-measurement: " + failure.message)
    }

    @Test
    fun aSingleLoadSpikeIsAbsorbed() {
        val estimates = scripted(9.0, 1.1, 1.2)
        PerfHarness.assertCrossEngineClassHolds("spike", ceiling, estimate = estimates.next)
        assertEquals(3, estimates.taken, "the spike is absorbed by the MEDIAN, not by the first clean re-run")
    }

    /**
     * **The mutant that survived.** With the ceiling never consulted, a genuine blowup passes — so this asserts
     * the comparison happens at all, which no other test in the estate did.
     */
    @Test
    fun aGenuineBlowupFailsOnEveryAttempt() {
        val estimates = scripted(12.0, 11.0, 13.0)
        assertThrows(AssertionError::class.java) {
            PerfHarness.assertCrossEngineClassHolds("blowup", ceiling, estimate = estimates.next)
        }
    }

    /**
     * **It gates on EVERY machine, hosted included** — the property the first version silently lost by defaulting
     * to `TimingEnforcement.forGrowthFloor()`, which returns `FLAGGED` whenever `CI` is set and turns a breach
     * into a println. A cross-engine ratio is dimensionless, so contention moves both arms and largely cancels;
     * that is why this family is excluded from the shared/isolated distinction in the first place.
     */
    @Test
    fun aBreachThrowsEvenWhenTheAmbientEnvironmentLooksHosted() {
        // The ambient CI value is deliberately not consulted: this family gates regardless of it.
        val estimates = scripted(9.0, 9.0, 9.0)
        assertThrows(AssertionError::class.java) {
            PerfHarness.assertCrossEngineClassHolds("hosted-breach", ceiling, estimate = estimates.next)
        }
    }

    @Test
    fun anEvenAttemptCountIsRefusedBecauseAMedianNeedsAMiddle() {
        assertThrows(IllegalArgumentException::class.java) {
            PerfHarness.assertCrossEngineClassHolds("even", ceiling, attempts = 2) { growth(1.0) }
        }
    }

    /** Growth is `ratioLarge / ratioSmall`, so hold the kernel arms at 1.0 and let the interpreter arms carry it. */
    private fun growth(g: Double) = PerfHarness.RatioGrowth(iSmall = 1.0, kSmall = 1.0, iLarge = g, kLarge = 1.0)

    private class Scripted(private val values: List<Double>) {
        var taken = 0
            private set

        val next: () -> PerfHarness.RatioGrowth = {
            val g = values[taken.coerceAtMost(values.lastIndex)]
            taken++
            PerfHarness.RatioGrowth(iSmall = 1.0, kSmall = 1.0, iLarge = g, kLarge = 1.0)
        }
    }

    private fun scripted(vararg values: Double) = Scripted(values.toList())
}
