package io.github.mbackschat.a12.dm.interpreter.footprint

import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.test.Test

class FootprintAggregateWorkerTest {

    @Test
    fun writeConfiguredAggregate() {
        if (!System.getenv("FOOTPRINT_AGGREGATE_ENABLED").equals("true", ignoreCase = true)) return

        val directory = requiredPath("FOOTPRINT_AGGREGATE_DIR")
        val output = requiredPath("FOOTPRINT_AGGREGATE_OUTPUT")
        val requiredForks = required("FOOTPRINT_AGGREGATE_FORKS").toIntOrNull()
            ?: throw IllegalArgumentException("FOOTPRINT_AGGREGATE_FORKS must be an integer")
        val inputs = FootprintHost.entries.flatMap { host ->
            (1..requiredForks).map { fork ->
                directory.resolve("${host.name.lowercase()}-$fork.json")
            }
        }
        val missing = inputs.filterNot(Files::isRegularFile)
        require(missing.isEmpty()) { "missing footprint worker result(s): $missing" }

        val forks = inputs.map { input ->
            FootprintProtocol.parse(Files.readString(input, StandardCharsets.UTF_8))
        }
        val aggregate = FootprintAggregation.aggregate(forks, requiredForks)
        writeAtomically(output, FootprintAggregation.render(aggregate))
    }

    private fun required(name: String): String =
        System.getenv(name)?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("$name is required for footprint aggregation")

    private fun requiredPath(name: String): Path = Path.of(required(name))

    private fun writeAtomically(output: Path, content: String) {
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
