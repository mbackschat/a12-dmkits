package io.github.mbackschat.a12.dm.adapter.perf

import com.sun.management.OperatingSystemMXBean
import java.lang.management.ManagementFactory

/**
 * How much of this machine somebody ELSE was using while an observation was measured — the `perfGateTest` arm of IF330.
 *
 * **Unlike [PerfEnvironmentQuality] beside it, this one is MEANT to gate, and the difference is not a preference.**
 * GC and JIT counters are *post-treatment*: an allocation regression causes them, so rejecting a sample on them
 * censors the evidence of the regression. Foreign CPU demand is *pre-treatment* — no change to our code can make
 * another process busy — so judging a comparison on it removes a confound instead of hiding one. That is why this
 * lives in its own object with its own fields rather than as three more keys on the quality window, which carries a
 * guard forbidding exactly the consumption this one requires.
 *
 * **Why a ratio could not do this job, measured 2026-09-20.** The interpreter protocol's occupancy floor
 * (`cpu / (wall − gc)`) is blind to moderate contention: four busy cores of eight inflate wall 37.5 % and move that
 * ratio only −4.0 %, because cycles stalled on a contended core are still charged to the measuring thread. In the
 * same conditions this quantity separates without overlap. The converse holds too — at two-times oversubscription
 * this reading saturates near 1.0 and stops ranking severity, where occupancy reads 0.44 — so the two instruments
 * are complementary and neither replaces the other. `docs/INTERPRETER-PERFORMANCE-RESULTS.md` carries the arms.
 *
 * **The one constraint the sampler cannot enforce for itself.** Both accessors compute a delta against the JVM's
 * own record of the previous call, so a second caller anywhere in this process would silently shorten our window.
 * `PerformanceRecordShapeGuardTest` therefore holds `getCpuLoad`/`getProcessCpuLoad` to this file alone; there is no
 * runtime check, because the corruption is invisible at runtime.
 */
internal object PerfMachineDemand {

    /**
     * The floor above which an absolute-anchor comparison is no longer this machine's to make.
     *
     * **Measured, with both directions and a return control (2026-09-20, darwin-aarch64, 8 cores, 24 wall-bounded
     * 200 ms windows per arm, applied load the only variable).** Unloaded arms read foreign demand 0.080–0.488 and
     * 0.193–0.591; four busy spinners read 0.851–0.896; sixteen read 0.903–0.959. So this sits 18 % above the
     * highest unloaded reading and 18 % below the lowest loaded one.
     *
     * **The unloaded maximum is high because the baseline machine is a workstation, not a quiesced box** — OS
     * metadata indexing alone took one to two cores throughout. A quieter machine would support a tighter floor;
     * re-deriving it is a measurement and never a preference, exactly as for the interpreter protocol's occupancy
     * floors: take the highest foreign reading a healthy capture produces on the machine in question and keep the
     * floor above it with margin. A floor set below that turns the anchor gate off on honest captures, which is how
     * a contamination check comes to be deleted instead of fixed.
     */
    internal const val MAX_FOREIGN_CPU_UTILISATION = 0.70

    private val os = ManagementFactory.getOperatingSystemMXBean() as OperatingSystemMXBean

    /**
     * One window's pair of load readings, as the JDK reported them — kept raw so every absence rule is testable.
     *
     * Both members are fractions of the whole machine, so `0.5` means four cores of eight, not half of one.
     */
    internal data class Reading(val system: Double, val process: Double) {

        /**
         * The system load, or `null` where the reading cannot be believed.
         *
         * A negative value is the JDK's documented "not available". **A zero is refused too, and that is measured
         * rather than defensive:** across 119 windows on 2026-09-20 in which a thread held a core throughout,
         * 17 returned exactly `0.0` — physically impossible — and the incidence was bursty rather than a rate
         * (0 of 35 windows in one sweep against 13 of 60 in another, with short windows and call interleaving both
         * refuted as the cause). Recording those as zero would tell a gate the machine was idle at precisely the
         * moments the instrument failed.
         */
        fun systemOrNull(): Double? = system.takeIf { it > 0.0 }

        /** This process's own load, or `null` for the JDK's sentinel. Zero is admitted: a window may hold no work of ours. */
        fun processOrNull(): Double? = process.takeIf { it >= 0.0 }

        /**
         * The load this process did not cause, or `null` where it cannot be derived.
         *
         * A system reading below our own is arithmetically impossible, so it is reported as unusable rather than
         * clamped to zero — the same rule [PerfEnvironmentQuality.delta] applies to a counter that went backwards,
         * for the same reason: a clamp presents a broken instrument as a quiet machine. The probe saw 0 of 47 usable
         * readings below own load, so this branch guards an instrument fault rather than an expected case.
         */
        val foreign: Double?
            get() {
                val system = systemOrNull() ?: return null
                val process = processOrNull() ?: return null
                return (system - process).takeIf { it >= 0.0 }
            }
    }

    /** Read both loads for the window since the previous call. Order is irrelevant; both deltas close here. */
    fun reading(): Reading = Reading(system = os.cpuLoad, process = os.processCpuLoad)

    /**
     * The recordable fields, or nothing at all when the window produced no usable reading.
     *
     * All three travel together or none does, so a reader never reconstructs one from the others and never mistakes
     * an absent key for a quiet machine — the same absence-versus-zero convention as the quality window's
     * Linux-only counters and the interpreter protocol's `gcMillis`.
     */
    fun fields(reading: Reading): Map<String, Any> {
        val foreign = reading.foreign ?: return emptyMap()
        return mapOf(
            "systemCpuUtilisation" to reading.systemOrNull()!!,
            "processCpuUtilisation" to reading.processOrNull()!!,
            "foreignCpuUtilisation" to foreign,
        )
    }
}
