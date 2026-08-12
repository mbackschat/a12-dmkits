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

    /**
     * A record's whole value is that a later run can be compared against it, and a measurement taken on an edited
     * tree did not measure the commit it names. `scripts/perf-anchor.sh` has marked that since it was written; the
     * record did not, so an uncommitted change could be compared against its own baseline with nothing saying so.
     */
    @Test
    fun anEditedTreeIsNamedAsSuchRatherThanBorrowingItsCommit() {
        assertEquals("abc1234", PerfObservations.commitLabel("abc1234", dirty = false))
        assertEquals("abc1234-DIRTY", PerfObservations.commitLabel("abc1234", dirty = true))
    }

    /**
     * …and the header genuinely applies that rule, checked against `git` run independently by this test so the
     * assertion holds on a clean checkout and on a working tree mid-edit alike. Asserting the marker outright
     * would pass only in the state the author happened to be in — which is the one state a guard must not assume.
     */
    @Test
    fun theRecordedCommitAgreesWithTheTreeItWasMeasuredOn() {
        val recorded = PerfObservations.headCommit()
        val dirty = ProcessBuilder("git", "status", "--porcelain", "--", ":/", ":(top,exclude)perf-results")
            .redirectErrorStream(true).start()
            .let { it.inputStream.bufferedReader().readText().trim().also { _ -> check(it.waitFor() == 0) } }
            .isNotEmpty()

        assertEquals(dirty, recorded.endsWith("-DIRTY"),
            "the record says \"$recorded\" while git reports the tree ${if (dirty) "edited" else "clean"} — a " +
                "record that names a commit it was not measured at is worse than one that names none")
    }

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
