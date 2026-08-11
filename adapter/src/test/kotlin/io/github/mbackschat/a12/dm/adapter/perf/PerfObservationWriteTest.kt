package io.github.mbackschat.a12.dm.adapter.perf

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * The performance record's write either happens or SAYS it did not.
 *
 * A discarded `runCatching` used to swallow every IO failure here, which made the single outcome the record exists
 * to prevent — a run that measures everything and stores nothing — report as success: the gate went green and the
 * history stayed empty, with nothing anywhere naming the cause. Cheap and untagged, so it runs on every push
 * rather than only inside the perf lane that consumes it.
 */
class PerfObservationWriteTest {

    @Test
    fun aWrittenLineIsTheRecordItWasGiven(@TempDir dir: File) {
        val target = dir.resolve("run.jsonl")
        PerfObservations.appendRecordTo(target, mapOf("kind" to "observation", "medianMs" to 1.5))
        PerfObservations.appendRecordTo(target, mapOf("kind" to "observation", "medianMs" to 2.5))

        val lines = target.readLines()
        assertEquals(2, lines.size, "each record is appended as its own line: $lines")
        assertEquals(1.5, PerfObservations.MAPPER.readTree(lines[0]).path("medianMs").asDouble())
        assertEquals(2.5, PerfObservations.MAPPER.readTree(lines[1]).path("medianMs").asDouble())
    }

    /**
     * The failing half, and the reason this file exists. A regular file standing where the record's DIRECTORY
     * should be is the portable way to make the append fail without permission games, and it is a real shape: the
     * results directory is resolved from a system property, so one pointed at a file produces exactly this.
     */
    @Test
    fun anUnwritableRecordFailsLoudlyInsteadOfBeingDiscarded(@TempDir dir: File) {
        val occupied = dir.resolve("not-a-directory").apply { writeText("") }
        val target = occupied.resolve("run.jsonl")

        val failure = assertThrows(IllegalStateException::class.java) {
            PerfObservations.appendRecordTo(target, mapOf("kind" to "observation", "medianMs" to 1.5))
        }

        assertTrue(failure.message!!.contains(target.toString())) {
            "the failure must name the file it could not write: ${failure.message}"
        }
        assertTrue(failure.cause != null) { "the underlying IO failure must stay attached as the cause" }
    }
}
