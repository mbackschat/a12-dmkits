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

    /**
     * **The record prunes itself to its retention bound, at write time.**
     *
     * The bound was enforced only by `PerformanceRecordShapeGuardTest`, which structurally cannot see the breach
     * it guards: a perf lane must run alone and therefore runs last, its record is written by that run, and the
     * record is committed *after* the session's estate check — so the run that crosses the horizon ships red by
     * construction. It did, on 2026-08-14, at 31 runs.
     */
    @Test
    fun theRecordPrunesItselfToTheRetentionBoundBeforeWritingAnother(@TempDir dir: File) {
        val env = dir.resolve("perf-results/darwin-aarch64").apply { mkdirs() }
        val stems = (1..PerfObservations.MAX_RUNS_PER_ENVIRONMENT).map { String.format("2026-01-%02dT000000Z", it) }
        stems.forEach { env.resolve("$it.jsonl").writeText("{}") }

        PerfObservations.pruneToRetentionBound(env)

        val left = env.listFiles()!!.map { it.nameWithoutExtension }.sorted()
        assertEquals(PerfObservations.MAX_RUNS_PER_ENVIRONMENT - 1, left.size,
            "one slot must be free for the run about to be written: $left")
        assertTrue(stems.first() !in left, "the OLDEST run is the one pruned: $left")
        assertTrue(stems.last() in left, "…and the newest is kept: $left")
    }

    /**
     * **A run some document CITES is never pruned**, whatever its age. `perf-results/README.md` states that past
     * the horizon the durable references are the committed anchor and the checkpoints that cite a run — so a
     * cited file is precisely the one that must survive its turn in the queue. Without this the prune would
     * silently break the citations that make the record worth keeping.
     */
    @Test
    fun aCitedRunSurvivesThePrune(@TempDir dir: File) {
        val env = dir.resolve("perf-results/darwin-aarch64").apply { mkdirs() }
        dir.resolve("docs").apply { mkdirs() }
            .resolve("INTERPRETER-PERFORMANCE.md")
            .writeText("The checkpoint measured `perf-results/darwin-aarch64/2026-01-01T000000Z.jsonl`.")
        val stems = (1..PerfObservations.MAX_RUNS_PER_ENVIRONMENT).map { String.format("2026-01-%02dT000000Z", it) }
        stems.forEach { env.resolve("$it.jsonl").writeText("{}") }

        PerfObservations.pruneToRetentionBound(env)

        val left = env.listFiles()!!.map { it.nameWithoutExtension }
        assertTrue(stems.first() in left, "the cited run is kept even though it is the oldest: $left")
        assertTrue(stems[1] !in left, "…and the next-oldest UNCITED run is pruned instead: $left")
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
     * **The warm outcome qualifies every cross-engine ratio in its lane, so it belongs beside the medians.**
     *
     * A still-falling kernel arm reads higher than its settled cost, which makes our ratio look better than it is
     * ([IF282]) — and until this record existed the outcome was only ever printed, so the 8 s cap's justification
     * lived in a `build/` log that `clean` deletes, a returning cap stop failed nothing, and no later run could
     * compare against it. That is IG121's fourth piece, and it stopped being hypothetical the day a `run-cap` stop
     * appeared in one of three gate runs with no history to say whether that lane had done it before.
     */
    @Test
    fun theWarmRecordNamesTheOutcomeThatQualifiesItsLanesRatios() {
        val record = PerfObservations.warmRecord(
            lane = "kernel parity",
            scenario = "diagnostic/CO-correlation@1 fingerprint=abc",
            n = 800,
            engine = "groovy",
            runs = 15,
            stop = "time-cap",
        )

        assertEquals(
            setOf("kind", "environment", "lane", "scenario", "n", "engine", "runs", "stop", "loadAverage"),
            record.keys,
            "a reader joins this to an observation on (lane, scenario, n, engine); dropping one makes it unjoinable",
        )
        assertEquals("warm", record["kind"])
        assertEquals("time-cap", record["stop"], "the CAP is the point — 'flattened' is the only outcome that is not a caveat")
        assertEquals(15, record["runs"])
    }

    /** What a lane cost to MEASURE, which is the other number IG121's fourth piece found only in run logs. */
    @Test
    fun theCostRecordNamesWhatItMeasuredAndHowLongItTook() {
        val record = PerfObservations.costRecord(lane = "kernel parity", scenario = "diagnostic/CO-correlation@1", wallMs = 8123.5)

        assertEquals(
            setOf("kind", "environment", "lane", "scenario", "wallMs", "loadAverage"),
            record.keys,
        )
        assertEquals("cost", record["kind"])
        assertEquals(8123.5, record["wallMs"])
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
