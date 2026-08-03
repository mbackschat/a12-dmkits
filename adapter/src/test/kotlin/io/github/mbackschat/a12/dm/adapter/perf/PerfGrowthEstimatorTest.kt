package io.github.mbackschat.a12.dm.adapter.perf

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class PerfGrowthEstimatorTest {

    @Test
    fun interleavesPairsAndSelectsTheOddMedianOfWithinPairRatios() {
        val scripted = ArrayDeque(listOf(1.0, 8.0, 2.0, 6.0, 100.0, 400.0, 200.0, 400.0, 300.0, 900.0))
        val scales = mutableListOf<Int>()

        val growth = PerfHarness.pairedGrowth(small = 10, large = 40, pairs = 5) { scale ->
            scales += scale
            scripted.removeFirst()
        }

        assertEquals(listOf(10, 40, 10, 40, 10, 40, 10, 40, 10, 40), scales)
        assertEquals(3.0, growth.ratio, 0.0, "median ratios [8, 3, 4, 2, 3]")
        assertEquals(100.0, growth.smallMs, 0.0)
        assertEquals(400.0, growth.largeMs, 0.0)
    }

    @Test
    fun rejectsInvalidEstimatorShapeAndObservations() {
        assertThrows<IllegalArgumentException> { PerfHarness.pairedGrowth(0, 40) { 1.0 } }
        assertThrows<IllegalArgumentException> { PerfHarness.pairedGrowth(40, 10) { 1.0 } }
        assertThrows<IllegalArgumentException> { PerfHarness.pairedGrowth(10, 40, pairs = 0) { 1.0 } }
        assertThrows<IllegalArgumentException> { PerfHarness.pairedGrowth(10, 40, pairs = 4) { 1.0 } }

        for (invalid in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertThrows<IllegalArgumentException>("invalid observation $invalid") {
                PerfHarness.pairedGrowth(10, 40, pairs = 1) { invalid }
            }
        }
    }

    @Test
    fun currentThreadCpuExcludesBlockedWallTime() {
        val wallStart = System.nanoTime()
        val measured = PerfHarness.measureCurrentThreadCpu(warmup = 0, iters = 1) {
            Thread.sleep(100)
        }.medianMs
        val wallMs = (System.nanoTime() - wallStart) / 1e6

        assertTrue(wallMs >= 80.0, "control: the thread must actually block, wall=${wallMs}ms")
        assertTrue(measured < wallMs / 4.0,
            "current-thread CPU must exclude blocked wall time: cpu=${measured}ms wall=${wallMs}ms")
    }
}
