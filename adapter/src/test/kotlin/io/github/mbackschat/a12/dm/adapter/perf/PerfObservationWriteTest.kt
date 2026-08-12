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
     * …and the header applies that rule to a REAL repository, in both states.
     *
     * The first version of this compared `headCommit()` against `git` invoked with the same pathspec by the test
     * itself. On a clean checkout that asserts `false == false`, which passes for an implementation that never
     * marks anything and cannot see a wrong pathspec at all — and a clean checkout is the only state CI runs in.
     * A throwaway repository is what makes the marker's two states, and the `perf-results` exclusion, all fire.
     */
    @Test
    fun theMarkerFiresOnAnEditedTreeAndNotOnACleanOne(@TempDir dir: File) {
        git(dir, "init", "--quiet")
        git(dir, "config", "user.email", "guard@example.invalid")
        git(dir, "config", "user.name", "guard")
        dir.resolve("tracked.txt").writeText("one\n")
        git(dir, "add", "-A")
        git(dir, "commit", "--quiet", "-m", "first")

        val clean = PerfObservations.headCommitIn(dir)
        assertTrue(clean.isNotEmpty() && !clean.endsWith("-DIRTY") && clean != "unknown",
            "a committed tree names its revision with no marker, was \"$clean\"")

        // The exclusion, exercised rather than asserted about: a gate run ALWAYS leaves a new file here, so if
        // this counted as dirt every honest record would be marked and the marker would mean nothing.
        dir.resolve("perf-results/darwin-aarch64").mkdirs()
        dir.resolve("perf-results/darwin-aarch64/run.jsonl").writeText("{}\n")
        assertEquals(clean, PerfObservations.headCommitIn(dir),
            "a new record under perf-results/ is this run's own output, not an edit to the revision it measures")

        dir.resolve("tracked.txt").writeText("two\n")
        assertEquals("$clean-DIRTY", PerfObservations.headCommitIn(dir),
            "and an edit anywhere else means the run did not measure the commit it names")
    }

    /** A directory that is not a repository cannot be labelled, and must not be labelled CLEAN. */
    @Test
    fun aTreeGitCannotReadIsUnknownRatherThanClean(@TempDir dir: File) {
        assertEquals("unknown", PerfObservations.headCommitIn(dir),
            "no revision is resolvable here; reporting a bare commit would invent one")
    }

    private fun git(dir: File, vararg args: String) {
        val process = ProcessBuilder(listOf("git", *args)).directory(dir).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { "git ${args.joinToString(" ")} failed in $dir: $output" }
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
     * **The header names the machine**, which is the field the rest of the mechanism rests on: the refresh script
     * refuses an input that omits it and the gated lane refuses a block that does not name one. Renaming or dropping
     * it here would leave every test green and surface only as a refresh dying with "does not name the machine it was
     * measured on" — a producer/consumer pair with no guard between them, which a cold review found.
     */
    @Test
    fun theRunHeaderNamesEveryConditionAConsumerReads() {
        val header = PerfObservations.runHeader("2026-08-12T120000Z")

        assertEquals(
            setOf("kind", "environment", "capturedAt", "commit", "os", "arch", "jdk", "processors", "machine",
                "loadAverage", "ci", "runId", "runnerName"),
            header.keys,
            "scripts/perf-anchor.sh reads environment, machine, processors, commit and jdk; perf-history.sh and " +
                "PerformanceRecordShapeGuardTest read the rest. A key renamed here is a consumer broken there",
        )
        assertEquals("run", header["kind"])
        assertEquals(PerfEnvironment.machine.model, header["machine"])
        assertTrue((header["machine"] as String).isNotBlank())
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
