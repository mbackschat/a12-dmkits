package io.github.mbackschat.a12.dm.adapter.perf

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * **The steady-state warm's stop rule, on scripted batch medians — no clock**, the same shape as
 * [PairedAcceptanceTest] and [ComparativeRetryTest] use for the estate's other acceptance rules.
 *
 * What it is for: the parity path took its first timed observation after three warm runs, which for the kernel's
 * Groovy validator is far below the point its per-call cost settles. Traced per pair on `CO-correlation`, **all
 * eighteen** engine-scale series across three sequential isolated runs were slower in pair 0 than in pair 2 —
 * groovy at N=800 by 1.51x-1.70x, the interpreter at N=200 by 1.88x-2.34x. The engines do not warm at the same
 * rate, so the cold pair does not cancel out of a cross-engine RATIO the way it cancels out of one engine's growth
 * factor; it moves the ratio by wherever on each warm curve the sample happened to land.
 */
class SteadyStateWarmUpTest {

    private fun budget(
        maxMillis: Double = 10_000.0,
        maxRuns: Int = 60,
        flatRatio: Double = 1.05,
    ) = WarmUpBudget(batch = 3, maxMillis = maxMillis, maxRuns = maxRuns, flatRatio = flatRatio)

    @Test
    fun warmingStopsOnceTheCurveHasFlattenedTWICE() {
        val warm = SteadyStateWarmUp(budget())

        assertFalse(warm.hasSettled(100.0), "the first batch has nothing to be compared against")
        assertFalse(warm.hasSettled(80.0), "still improving by 1.25x — the engine has not settled")
        assertFalse(warm.hasSettled(78.0), "80/78 = 1.026 is flat, but one flat probe is one observation")
        assertTrue(warm.hasSettled(77.5), "and the second consecutive one confirms it")
        assertEquals(12, warm.runs, "four batches of three, and the caller pays for exactly those")
    }

    @Test
    fun oneFlatProbeInTheMiddleOfAWARMINGCurveDoesNotEndItEarly() {
        val warm = SteadyStateWarmUp(budget())

        assertFalse(warm.hasSettled(100.0))
        assertFalse(warm.hasSettled(99.0), "a noisy probe that happens to look flat")
        assertFalse(warm.hasSettled(50.0), "and the engine was in fact still warming — the run must resume")
        assertFalse(warm.hasSettled(49.5), "so the flat count starts again from here")
        assertTrue(warm.hasSettled(49.0))
    }

    @Test
    fun aBatchThatGotWORSECountsAsFlatRatherThanExtendingTheWarm() {
        val warm = SteadyStateWarmUp(budget())

        assertFalse(warm.hasSettled(100.0))
        assertFalse(warm.hasSettled(120.0), "past the improving phase, but still only one observation of that")
        assertTrue(warm.hasSettled(118.0),
            "confirmed: whatever this is, it is no longer JIT warming, and warming through noise would spend the " +
                "budget chasing it")
    }

    @Test
    fun aCheapInstanceIsStoppedByTheRunCapRatherThanSpinning() {
        val warm = SteadyStateWarmUp(budget(maxRuns = 12))

        // Halving every batch: the flattening rule never fires, so only a cap can end this.
        assertFalse(warm.hasSettled(100.0))
        assertFalse(warm.hasSettled(50.0))
        assertFalse(warm.hasSettled(25.0))
        assertTrue(warm.hasSettled(12.5), "12 runs is the cap, and a sub-millisecond instance would otherwise " +
            "keep improving for thousands of them")
        assertEquals(12, warm.runs)
    }

    @Test
    fun anExpensiveInstanceIsStoppedByTheTimeCapRatherThanTheRunCap() {
        val warm = SteadyStateWarmUp(budget(maxMillis = 1_000.0, flatRatio = 1.001))

        assertFalse(warm.hasSettled(200.0), "600ms spent")
        assertFalse(warm.hasSettled(100.0), "900ms spent")
        assertTrue(warm.hasSettled(50.0), "1050ms spent, past the budget — an expensive scenario must not warm " +
            "for its own run cap's worth of 200ms evaluations")
        assertEquals(9, warm.runs, "and the run cap has not been reached")
    }

    @Test
    fun theDriverRunsTheInstanceInWholeBatchesAndReportsWhatItSpent() {
        var calls = 0
        val instance = object : RunInstance {
            override fun outcome() = PerfOutcome(listOf("x")).also { calls++ }
        }

        val runs = PerfHarness.warmToSteadyState(instance, budget(maxRuns = 12))

        assertEquals(calls, runs, "the reported cost is what the instance was actually asked to do")
        assertEquals(0, runs % 3, "whole batches only: a partial batch has no median to judge flattening by")
        assertTrue(runs in 3..12, "at least one batch, never past the cap, was $runs")
    }
}
