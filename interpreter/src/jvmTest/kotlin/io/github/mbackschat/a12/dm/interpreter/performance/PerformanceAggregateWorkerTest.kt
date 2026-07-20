package io.github.mbackschat.a12.dm.interpreter.performance

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.test.Test

class PerformanceAggregateWorkerTest {

    @Test
    fun aggregateConfiguredProcessSamples() {
        if (!System.getenv("PERFORMANCE_AGGREGATE_ENABLED").equals("true", ignoreCase = true)) return
        fun required(name: String): String =
            System.getenv(name)?.takeIf { it.isNotBlank() }
                ?: throw IllegalArgumentException("$name is required for performance aggregation")

        val directory = Path.of(required("PERFORMANCE_AGGREGATE_DIR"))
        val output = Path.of(required("PERFORMANCE_AGGREGATE_OUTPUT"))
        val requiredForks = required("PERFORMANCE_AGGREGATE_FORKS").toInt()
        val forks = directory.listDirectoryEntries()
            .filter { it.isRegularFile() && it.extension == "json" }
            .sorted()
            .map { PerformanceProtocol.parse(Files.readString(it, StandardCharsets.UTF_8)) }
        val run = PerformanceProtocol.aggregate(forks, requiredForks)
        Files.createDirectories(output.parent)
        Files.writeString(output, PerformanceProtocol.render(run), StandardCharsets.UTF_8)
    }
}
