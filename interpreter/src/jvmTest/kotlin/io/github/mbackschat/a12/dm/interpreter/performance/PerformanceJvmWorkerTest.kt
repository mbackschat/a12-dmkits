package io.github.mbackschat.a12.dm.interpreter.performance

import java.lang.management.ManagementFactory
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlin.test.Test

class PerformanceJvmWorkerTest {

    @Test
    fun writeConfiguredProcessSample() {
        val platform = JvmPerformancePlatform()
        if (!PerformanceWorkerRequest.enabled(platform)) return
        val request = PerformanceWorkerRequest.fromEnvironment(platform)
        val fork = PerformanceWorker.run(request.config, platform)
        platform.write(request.outputPath, PerformanceProtocol.render(fork))
    }
}

internal class JvmPerformancePlatform : PerformancePlatform {
    override val host = PerformanceHost.JVM

    override fun environment(name: String): String? = System.getenv(name)

    override fun runtime(): PerformanceRuntime = PerformanceRuntime(
        name = System.getProperty("java.vm.name"),
        version = Runtime.version().toString(),
        os = System.getProperty("os.name"),
        arch = System.getProperty("os.arch"),
        pid = ProcessHandle.current().pid(),
        maxHeapBytes = Runtime.getRuntime().maxMemory(),
    )

    override fun nanoTime(): Long = System.nanoTime()

    /**
     * Measuring-thread CPU time from the platform MXBean.
     *
     * `getCurrentThreadCpuTime` returns -1 when thread CPU measurement is disabled, which would silently read
     * as zero occupancy and fail every process. That is a broken instrument rather than a contended machine,
     * so it is refused by name — the bar it feeds is worthless either way, and the two causes want different
     * fixes.
     */
    override fun cpuNanoTime(): Long {
        val threads = ManagementFactory.getThreadMXBean()
        require(threads.isCurrentThreadCpuTimeSupported) {
            "this JVM does not support thread CPU time, so a sample's occupancy cannot be measured"
        }
        val cpu = threads.currentThreadCpuTime
        require(cpu >= 0) { "thread CPU time is disabled on this JVM, so occupancy cannot be measured" }
        return cpu
    }

    /**
     * The sum of every collector's accumulated elapsed time.
     *
     * A bean returns -1 when it cannot supply a figure, and adding that would silently shrink the total — the
     * same guard `PerfEnvironmentQuality` applies to its own GC counters. This is collection ELAPSED time rather
     * than stop-the-world pause time, which a concurrent collector reports for phases that never stopped the
     * measuring thread, so it is an UPPER bound on the pause a region paid. That direction is the safe one here:
     * it cannot hide GC, only overstate it.
     */
    override fun cumulativeGcMillis(): Double =
        ManagementFactory.getGarbageCollectorMXBeans()
            .sumOf { bean -> bean.collectionTime.takeIf { it >= 0L } ?: 0L }
            .toDouble()

    override fun sha256(content: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(content.toByteArray(StandardCharsets.UTF_8))
            .joinToString(separator = "") { "%02x".format(it) }

    override fun write(path: String, content: String) {
        val output = Path.of(path)
        Files.createDirectories(output.parent)
        val temporary = Files.createTempFile(output.parent, "${output.fileName}.", ".tmp")
        try {
            Files.writeString(temporary, content, StandardCharsets.UTF_8)
            try {
                Files.move(
                    temporary,
                    output,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
}
