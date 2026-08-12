package io.github.mbackschat.a12.dm.adapter.perf

import java.io.File
import java.lang.management.ManagementFactory

/**
 * Environment-quality counters recorded beside every observation — IG121's third piece.
 *
 * **These are RECORDED and never used to reject a sample, and that is a correctness rule rather than caution.**
 * GC and JIT activity are *post-treatment* variables: an allocation regression causes GC, so discarding windows
 * that contain GC would censor exactly the evidence that proves the regression. The same holds for steal time on a
 * shared host, which correlates with the load the run was competing against. So nothing here may ever appear in a
 * bar, a floor, or a filter — [PerformanceRecordShapeGuardTest] enforces that the field names are read only by the
 * recorder, because "recorded, never used to reject" is the kind of claim that decays silently once the numbers are
 * available to a gate author.
 *
 * **What the window actually is, stated exactly.** A delta is taken between one observation write and the
 * *previous* one, so the window CONTAINS that observation's measurement plus whatever harness work ran between
 * them — in the parity lane that includes the other engines' arms. It is therefore a **qualifier on the
 * neighbourhood of a measurement, not an isolate of it**, and `windowSince` records which of the two definitions a
 * row carries (`jvm-start` for the first observation in a run). Naming it for what it measures is deliberate: an
 * `...InWindow` figure invites no per-engine attribution, and a field called `gcMsDuringThisMeasurement` would be
 * a false claim about a number nobody isolated.
 *
 * **Portability.** GC and JIT come from the JVM's own beans and exist everywhere. Steal time and cgroup throttling
 * are Linux-only (`/proc/stat`, `/sys/fs/cgroup/cpu.stat`) and stay **null** on macOS rather than defaulting to 0 —
 * a 0 would read as "no steal observed" where the truth is "not observable here", which is the same
 * absence-versus-zero confusion the four vacuous list-in-key memos were closed on ([IF285]).
 */
internal object PerfEnvironmentQuality {

    /** A point-in-time read of every counter. `null` means the platform does not expose it. */
    internal data class Snapshot(
        val gcCount: Long,
        val gcMs: Long,
        val jitMs: Long?,
        val stealJiffies: Long?,
        val throttledUsec: Long?,
        val throttledPeriods: Long?,
    )

    fun snapshot(): Snapshot {
        var gcCount = 0L
        var gcMs = 0L
        for (bean in ManagementFactory.getGarbageCollectorMXBeans()) {
            // A bean returns -1 when it cannot supply a count; adding that would silently shrink the total.
            bean.collectionCount.takeIf { it >= 0 }?.let { gcCount += it }
            bean.collectionTime.takeIf { it >= 0 }?.let { gcMs += it }
        }
        val jit = ManagementFactory.getCompilationMXBean()
            ?.takeIf { it.isCompilationTimeMonitoringSupported }
            ?.totalCompilationTime
        val cpuStat = readCgroupCpuStat()
        return Snapshot(
            gcCount = gcCount,
            gcMs = gcMs,
            jitMs = jit,
            stealJiffies = readStealJiffies(),
            throttledUsec = cpuStat["throttled_usec"],
            throttledPeriods = cpuStat["nr_throttled"],
        )
    }

    /**
     * The recordable delta from [before] to [after]. A counter absent on this platform stays absent; a counter that
     * went backwards (a bean reset, or a cgroup file that rotated) is reported as `null` rather than as a negative,
     * because a negative delta is not a smaller measurement — it is an unusable one.
     */
    fun delta(before: Snapshot?, after: Snapshot): Map<String, Any?> = mapOf(
        "windowSince" to if (before == null) "jvm-start" else "previous-observation",
        "gcCountInWindow" to diff(before?.gcCount ?: 0L, after.gcCount),
        "gcMsInWindow" to diff(before?.gcMs ?: 0L, after.gcMs),
        "jitMsInWindow" to diff(before?.jitMs, after.jitMs),
        "stealJiffiesInWindow" to diff(before?.stealJiffies, after.stealJiffies),
        "cgroupThrottledUsecInWindow" to diff(before?.throttledUsec, after.throttledUsec),
        "cgroupThrottledPeriodsInWindow" to diff(before?.throttledPeriods, after.throttledPeriods),
    )

    /** `before == null` with a present `after` means "first window in this run" — measure from JVM start (0). */
    private fun diff(before: Long?, after: Long?): Long? {
        if (after == null) return null
        val from = before ?: 0L
        return (after - from).takeIf { it >= 0 }
    }

    /** `/proc/stat`'s aggregate line: `cpu user nice system idle iowait irq softirq steal …` — steal is field 8. */
    private fun readStealJiffies(): Long? = runCatching {
        val line = File("/proc/stat").takeIf { it.canRead() }
            ?.useLines { lines -> lines.firstOrNull { it.startsWith("cpu ") } }
            ?: return null
        line.trim().split(Regex("\\s+")).getOrNull(8)?.toLongOrNull()
    }.getOrNull()

    /** cgroup v2 `cpu.stat`, then v1's `cpu.cfs` peer; absent outside a container and on macOS. */
    private fun readCgroupCpuStat(): Map<String, Long> = runCatching {
        val file = sequenceOf("/sys/fs/cgroup/cpu.stat", "/sys/fs/cgroup/cpu/cpu.stat")
            .map(::File).firstOrNull { it.canRead() } ?: return emptyMap()
        file.useLines { lines ->
            lines.mapNotNull { line ->
                val parts = line.trim().split(' ')
                val value = parts.getOrNull(1)?.toLongOrNull()
                if (parts.size >= 2 && value != null) parts[0] to value else null
            }.toMap()
        }
    }.getOrElse { emptyMap() }
}
