package io.github.mbackschat.a12.dm.adapter.perf

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test

/**
 * Both measuring loops report the bytes the measured block allocated on the measuring thread (INTERPRETER-PERFORMANCE,
 * "Memory growth is barred": all three regressions of 2026-10-13 showed first in allocation).
 *
 * The oracle is a block whose allocation is known from its own source: a 1 MiB array per run. The control is the
 * same loop around an empty block, which must report far less, so a reading that ignored the block could not pass.
 * The memory arms that bar these readings are scripted below with known byte counts on both sides of each bound.
 */
class PerfAllocationMeasurementTest {

    private var sink: ByteArray? = null

    private fun allocateOneMebibyte() {
        sink = ByteArray(MEBIBYTE)
    }

    private fun bytesOf(stats: Stats): Long =
        stats.allocatedBytes ?: fail("this JVM reports no thread allocation, which the pinned OpenJDK 21 does")

    @Test
    fun theWallClockLoopReportsWhatTheBlockAllocated() {
        val allocating = bytesOf(PerfHarness.measure(warmup = 1, iters = 3, block = ::allocateOneMebibyte))
        val empty = bytesOf(PerfHarness.measure(warmup = 1, iters = 3) { })
        assertTrue(allocating >= MEBIBYTE, "a 1 MiB array per run reported $allocating bytes")
        assertTrue(empty < MEBIBYTE / 16, "an empty block reported $empty bytes")
    }

    @Test
    fun theCpuClockLoopReportsWhatTheBlockAllocated() {
        val allocating = bytesOf(PerfHarness.measureCurrentThreadCpu(warmup = 1, iters = 3, block = ::allocateOneMebibyte))
        assertTrue(allocating >= MEBIBYTE, "a 1 MiB array per run reported $allocating bytes")
    }

    /** The memory arms bar growth above the floor, admit linear growth, and leave a lane under the noise floor alone. */
    @Test
    fun aMemoryArmBarsGrowthAboveTheFloorOnlyAboveTheNoiseFloor() {
        fun breach(atSmall: Long, atLarge: Long) =
            PerfHarness.memoryGrowthBreach("lane", "arm", 1000, 4000, mapOf(1000 to atSmall, 4000 to atLarge), 9.0)
        val mebibyte = MEBIBYTE.toLong()
        assertTrue(breach(mebibyte, 16 * mebibyte) != null, "quadratic growth above the noise floor passed")
        assertTrue(breach(mebibyte, 4 * mebibyte) == null, "linear growth was refused")
        assertTrue(breach(mebibyte / 64, mebibyte) == null, "a lane under the noise floor was refused")
        assertTrue(breach(mebibyte / 64, 2 * mebibyte) != null, "a quadratic that starts small passed above the noise floor")
        assertTrue(PerfHarness.memoryGrowthBreach("lane", "arm", 1000, 4000, mapOf(1000 to mebibyte), 9.0) == null,
            "a missing reading was refused")
    }

    private companion object {
        const val MEBIBYTE = 1 shl 20
    }
}
