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

    /**
     * The measured classes were compiled from the tree the build STARTED with, so that state decides the label.
     * The incident shape: edits present at build start, stashed before the header was written, so the header alone
     * read a clean tree and named the parent commit.
     */
    @Test
    fun anEditedTreeAtBuildStartMarksTheRecordWhateverTheHeaderSees() {
        assertEquals("c223b0f-DIRTY", PerfObservations.measuredLabel("c223b0f-DIRTY", atHeader = "c223b0f"))
        assertEquals("c223b0f-DIRTY", PerfObservations.measuredLabel("c223b0f", atHeader = "c223b0f-DIRTY"))
        assertEquals("c223b0f", PerfObservations.measuredLabel("c223b0f", atHeader = "c223b0f"))
        // A commit made during the run moves HEAD, not the classes already compiled from the build-start tree.
        assertEquals("c223b0f", PerfObservations.measuredLabel("c223b0f", atHeader = "abdc13a"))
        assertEquals("abdc13a", PerfObservations.measuredLabel(null, atHeader = "abdc13a"))
    }

    /** The isolated lane refuses a missing build-start label; a broad run, which judges no anchor, may lack it. */
    @Test
    fun theIsolatedLaneRefusesToRecordWithoutTheBuildStartLabel() {
        assertEquals("c223b0f", PerfObservations.buildStartLabel("c223b0f", isolatedLane = true))
        assertEquals(null, PerfObservations.buildStartLabel(null, isolatedLane = false))
        assertThrows(IllegalStateException::class.java) { PerfObservations.buildStartLabel(null, isolatedLane = true) }
        assertThrows(IllegalStateException::class.java) { PerfObservations.buildStartLabel("", isolatedLane = true) }
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
        commitAll(dir)

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
        commitAll(dir)

        PerfObservations.pruneToRetentionBound(env)

        val left = env.listFiles()!!.map { it.nameWithoutExtension }
        assertTrue(stems.first() in left, "the cited run is kept even though it is the oldest: $left")
        assertTrue(stems[1] !in left, "…and the next-oldest UNCITED run is pruned instead: $left")
    }

    /**
     * **A record nobody has committed yet is never pruned.** Retention bounds the committed history; an untracked
     * record is a run still awaiting the maintainer's decision to keep it. When every committed run was cited, the
     * oldest uncited run was the newest one, so on 2026-10-21 a filtered diagnostic run deleted the full gate run
     * written half an hour before it. With nothing committed left to prune, the writer names the cited runs to
     * retire instead ([aSaturatedRecordNamesTheCitedRunsToRetire]).
     */
    @Test
    fun anUncommittedRunSurvivesThePruneEvenWhenEveryCommittedRunIsCited(@TempDir dir: File) {
        val env = dir.resolve("perf-results/darwin-aarch64").apply { mkdirs() }
        val committed = (1 until PerfObservations.MAX_RUNS_PER_ENVIRONMENT).map { String.format("2026-01-%02dT000000Z", it) }
        committed.forEach { env.resolve("$it.jsonl").writeText("{}") }
        dir.resolve("docs").apply { mkdirs() }.resolve("CHECKPOINTS.md").writeText(committed.joinToString("\n") { "`$it`" })
        commitAll(dir)
        val pending = env.resolve("2026-02-01T000000Z.jsonl").apply { writeText("{}") }

        PerfObservations.pruneToRetentionBound(env)

        assertTrue(pending.exists(), "the uncommitted gate run must survive its successor's prune")
        assertEquals(PerfObservations.MAX_RUNS_PER_ENVIRONMENT, env.listFiles()!!.size,
            "and no cited committed run is deleted to make room either")
    }

    /**
     * **The bound counts committed runs only, and a staged run is not committed.** Untracked ad-hoc runs used to
     * count toward the bound, so five of them beside twenty-five committed runs cost one committed run its place;
     * and the index is not history, so a run that was only `git add`ed counted as committed and could be pruned.
     */
    @Test
    fun theBoundCountsCommittedRunsAndAStagedRunIsNotOne(@TempDir dir: File) {
        val env = dir.resolve("perf-results/darwin-aarch64").apply { mkdirs() }
        val committed = (1..25).map { String.format("2026-01-%02dT000000Z", it) }
        committed.forEach { env.resolve("$it.jsonl").writeText("{}") }
        commitAll(dir)
        val adHoc = (1..5).map { env.resolve(String.format("2026-02-%02dT000000Z.jsonl", it)).apply { writeText("{}") } }
        git(dir, "add", "--", adHoc.first().relativeTo(dir).path)

        val blocking = PerfObservations.pruneToRetentionBound(env)

        assertEquals(30, env.listFiles()!!.size, "nothing is pruned while 25 committed runs leave room")
        assertEquals(emptyList<String>(), blocking)
    }

    /**
     * **A saturated record SAYS so.** When every committed run is cited the prune can delete nothing, and the run
     * about to be written would carry the environment past the bound; the guard counting files only sees it after
     * the fact, so the writer names the cited runs a maintainer has to retire.
     */
    @Test
    fun aSaturatedRecordNamesTheCitedRunsToRetire(@TempDir dir: File) {
        val env = dir.resolve("perf-results/darwin-aarch64").apply { mkdirs() }
        val committed = (1..PerfObservations.MAX_RUNS_PER_ENVIRONMENT).map { String.format("2026-01-%02dT000000Z", it) }
        committed.forEach { env.resolve("$it.jsonl").writeText("{}") }
        dir.resolve("docs").apply { mkdirs() }.resolve("CHECKPOINTS.md").writeText(committed.joinToString("\n") { "`$it`" })
        commitAll(dir)
        val staged = env.resolve("2026-02-01T000000Z.jsonl").apply { writeText("{}") }
        git(dir, "add", "--", staged.relativeTo(dir).path)

        assertEquals(committed.take(1), PerfObservations.pruneToRetentionBound(env),
            "one slot is needed and only the oldest cited run would free it")
        assertTrue(staged.exists(), "a staged run is not committed history, so it is not the prune's to delete")
    }

    /** Outside a repository nothing is known to be committed, so nothing is pruned: a prune is best-effort. */
    @Test
    fun aTreeGitCannotReadPrunesNothing(@TempDir dir: File) {
        val env = dir.resolve("perf-results/darwin-aarch64").apply { mkdirs() }
        (1..PerfObservations.MAX_RUNS_PER_ENVIRONMENT).forEach { env.resolve(String.format("2026-01-%02dT000000Z.jsonl", it)).writeText("{}") }

        PerfObservations.pruneToRetentionBound(env)

        assertEquals(PerfObservations.MAX_RUNS_PER_ENVIRONMENT, env.listFiles()!!.size)
    }

    private fun commitAll(dir: File) {
        git(dir, "init", "--quiet")
        git(dir, "config", "user.email", "guard@example.invalid")
        git(dir, "config", "user.name", "guard")
        git(dir, "add", "-A")
        git(dir, "commit", "--quiet", "-m", "record")
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
            setOf("kind", "environment", "capturedAt", "commit", "os", "arch", "jdk", "jdkFamily", "processors",
                "machine", "loadAverage", "ci", "runId", "runnerName"),
            header.keys,
            "scripts/perf-anchor.sh reads environment, machine, processors, commit and jdk; perf-history.sh and " +
                "PerformanceRecordShapeGuardTest read the rest. A key renamed here is a consumer broken there",
        )
        assertEquals("run", header["kind"])
        assertEquals(PerfEnvironment.machine.model, header["machine"])
        assertTrue((header["machine"] as String).isNotBlank())
        // `jdk` alone cannot identify the runtime: Oracle GraalVM 21 reports 21.0.12 and Homebrew OpenJDK reports
        // 21.0.12.1, so the strings differ by coincidence rather than by family — and a record that cannot name
        // its VM family is one a later reader cannot use to explain a step change, which cost a day on
        // 2026-08-23. The gate itself is JdkIdentity.requireSupported; this field is the provenance beside it.
        assertEquals(JdkIdentity.current.family, header["jdkFamily"])
        assertEquals("OpenJDK", header["jdkFamily"], "perf records are only ever produced on OpenJDK 21")
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
