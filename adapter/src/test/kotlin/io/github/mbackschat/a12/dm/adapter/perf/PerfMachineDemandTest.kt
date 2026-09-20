package io.github.mbackschat.a12.dm.adapter.perf

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The foreign-demand reading's shape, on scripted inputs — the live sampler is exercised by [aLiveReadingIsCoherent].
 *
 * Every absence rule below has a measured basis in the 2026-09-20 probe, not a convention: `getCpuLoad()` was
 * observed returning a physically impossible `0.0` while the calling thread held a core, so a zero SYSTEM reading
 * is unusable, while a zero PROCESS reading is a legitimate value the same probe never saw contradicted.
 */
class PerfMachineDemandTest {

    @Test
    fun foreignDemandIsWhatTHISProcessDidNotConsume() {
        val reading = PerfMachineDemand.Reading(system = 0.675, process = 0.125)

        assertEquals(0.55, reading.foreign!!, 1e-9, "foreign demand is the system load this process did not cause")
    }

    /**
     * A zero SYSTEM reading is refused, because it cannot be true while the recorder itself is running.
     *
     * Measured over 119 windows on 2026-09-20: 17 returned `0.0` with a thread burning CPU throughout, and the
     * incidence was bursty rather than a rate (0 of 35 in one sweep, 13 of 60 in another). Recording it as zero
     * would tell a gate the machine was idle at exactly the moments the instrument failed.
     */
    @Test
    fun aZeroSystemReadingIsUnusableRatherThanQuiet() {
        assertNull(PerfMachineDemand.Reading(system = 0.0, process = 0.125).foreign)
        assertNull(PerfMachineDemand.Reading(system = 0.0, process = 0.125).systemOrNull())
    }

    /** The JDK's documented sentinel for "not available" on either accessor. */
    @Test
    fun aNegativeReadingIsUnusableOnBothAccessors() {
        assertNull(PerfMachineDemand.Reading(system = -1.0, process = 0.125).foreign)
        assertNull(PerfMachineDemand.Reading(system = 0.675, process = -1.0).foreign)
    }

    /**
     * A system load below this process's own is arithmetically impossible, so it is reported as unusable rather
     * than clamped to zero — the same rule `PerfEnvironmentQuality.delta` applies to a counter that went backwards,
     * because a clamp would present a broken instrument as a quiet machine. The probe saw 0 of 47 usable readings
     * below own load, so this is a guard against an instrument fault, not an expected case.
     */
    @Test
    fun aSystemReadingBelowThisProcessesOwnIsUnusableRatherThanClamped() {
        assertNull(PerfMachineDemand.Reading(system = 0.10, process = 0.30).foreign)
    }

    /** A process load of zero is a real value: the window may have contained no work of ours. */
    @Test
    fun aZeroProcessReadingIsAdmitted() {
        assertEquals(0.20, PerfMachineDemand.Reading(system = 0.20, process = 0.0).foreign!!, 1e-9)
    }

    /**
     * The recorded field set omits what it cannot say, so a reader cannot mistake absence for zero.
     *
     * Same convention as `gcMillis` in the interpreter protocol and the Linux-only counters beside it: the key is
     * absent, never present-and-zero.
     */
    @Test
    fun anUnusableReadingWritesNoFieldsAtAll() {
        assertEquals(emptyMap<String, Any>(), PerfMachineDemand.fields(PerfMachineDemand.Reading(system = 0.0, process = 0.1)))
    }

    @Test
    fun aUsableReadingWritesAllThreeFieldsTogether() {
        val fields = PerfMachineDemand.fields(PerfMachineDemand.Reading(system = 0.675, process = 0.125))

        assertEquals(
            setOf("systemCpuUtilisation", "processCpuUtilisation", "foreignCpuUtilisation"), fields.keys,
            "the three travel together, so a reader never has to reconstruct one from the others",
        )
    }

    /**
     * The live sampler produces a coherent reading on this machine — the positive control for the whole mechanism.
     *
     * Deliberately asserts only coherence, never a magnitude: the reading is the machine's, and a bar on it here
     * would fail on a busy workstation exactly like the `loadAverage` check this instrument replaces. A window with
     * no reading at all is an accepted outcome, for the burst reason the zero test above records, so the assertion
     * is over several windows and requires that at least one of them says something.
     */
    @Test
    fun aLiveReadingIsCoherent() {
        val readings = (0 until 8).map {
            burn(60)
            PerfMachineDemand.reading()
        }
        val usable = readings.mapNotNull { it.foreign }

        assertTrue(
            usable.isNotEmpty(),
            "eight windows produced no usable system-load reading at all; the instrument is broken here, not quiet",
        )
        assertTrue(
            usable.all { it >= 0.0 },
            "a usable foreign demand is never negative by construction; was $usable",
        )
    }

    /** Wall-bounded, so a slow machine does not make this test slower — only less loaded. */
    private fun burn(millis: Long) {
        val until = System.nanoTime() + millis * 1_000_000L
        var sink = 0.0
        while (System.nanoTime() < until) {
            for (index in 0 until 10_000) sink += kotlin.math.sqrt(index.toDouble())
        }
        check(sink >= 0.0)
    }
}
