package io.github.mbackschat.a12.dm.adapter.perf

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The re-measurement rule the CROSS-ENGINE bars use, on scripted samples rather than a clock.
 *
 * Why the comparative bars need one at all: the estate's tightest row is the compute-overlay `$`-correlation
 * path, and before the steady-state warm it came within `1.35x` of a loss while spreading `1.90x` across three
 * sequential isolated runs — *the noise was larger than the margin*, so a single sample there was a coin flip on
 * a gate that must never false-red. The warm inverted that (margin `1.99x`, spread `1.036x`, re-measured 2026-08-12); this rule stays as
 * the backstop, since a warm instance is still a timed one. It is the growth floor's rule, reused rather than
 * reinvented: retry only on a breach, and accept the MEDIAN of the attempts, because retrying until one attempt
 * passes makes a real loss more likely to be admitted the more often it is retried.
 */
class ComparativeRetryTest {

    @Test
    fun aCleanFirstSampleCostsExactlyOneMeasurement() {
        val taken = mutableListOf<Double>()
        val outcome = PerfHarness.acceptOnMedianOfRetries(
            breaches = { it >= 1.0 }, rank = { it },
        ) { 0.6.also { taken += it } }

        assertEquals(1, taken.size, "a passing comparison must not pay for re-measurements it does not need")
        assertEquals(0.6, outcome.accepted)
    }

    @Test
    fun aContendedSliceIsAbsorbedInsteadOfReddeningTheBuild() {
        val scripted = ArrayDeque(listOf(1.05, 0.62, 0.60))

        val outcome = PerfHarness.acceptOnMedianOfRetries(breaches = { it >= 1.0 }, rank = { it }) {
            scripted.removeFirst()
        }

        assertEquals(0.62, outcome.accepted, "the median of the attempts, so one contended sample cannot red a gate")
        assertEquals(3, outcome.taken.size)
        assertTrue(outcome.accepted < 1.0, "and the comparison therefore passes")
    }

    @Test
    fun oneLowOutlierCannotClearARealLossToTheKernel() {
        val scripted = ArrayDeque(listOf(1.20, 1.15, 0.80))

        val outcome = PerfHarness.acceptOnMedianOfRetries(breaches = { it >= 1.0 }, rank = { it }) {
            scripted.removeFirst()
        }

        assertEquals(1.15, outcome.accepted,
            "the whole correctness of the rule: a genuine loss survives its own re-measurement, so retrying " +
                "cannot launder it the way a first-attempt-that-passes rule would")
        assertTrue(outcome.accepted >= 1.0, "so the comparison still fails")
    }

    @Test
    fun anEvenAttemptCountHasNoMedianAndIsRejected() {
        val failure = runCatching {
            PerfHarness.acceptOnMedianOfRetries(attempts = 2, breaches = { true }, rank = { it }) { 1.0 }
        }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException && "odd" in failure.message.orEmpty()) { "$failure" }
    }

    /** The one number a whole sample is judged by: worst over both scales and both kernel strategies. */
    @Test
    fun aSampleIsRankedByItsWorstRatioAcrossBothScalesAndBothStrategies() {
        val sample = PerfHarness.ParitySample(pairs = 1).apply {
            interpSmall[0] = 1.0; groovySmall[0] = 10.0; javaSmall[0] = 5.0     // 0.10 / 0.20
            interpLarge[0] = 4.0; groovyLarge[0] = 8.0; javaLarge[0] = 5.0      // 0.50 / 0.80
        }

        assertEquals(0.8, sample.worstInterpreterRatio(), 1e-9,
            "a loss anywhere is a loss: the rank must be the WORST cell, never an average that a strong scale " +
                "or a slow strategy could hide")
    }
}
