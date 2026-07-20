package io.github.mbackschat.a12.dm.interpreter.performance

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
