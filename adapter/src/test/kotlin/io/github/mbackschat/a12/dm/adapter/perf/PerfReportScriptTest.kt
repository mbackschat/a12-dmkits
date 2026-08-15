package io.github.mbackschat.a12.dm.adapter.perf

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * `scripts/perf-report.sh` — IG137 piece (b), **executed** against synthetic records like its siblings.
 *
 * The script is a COMPOSITION: sections 2-5 delegate to `perf-history.sh`, `perf-trend.sh` (twice) and
 * `perf-anchor.sh show`, each of which has its own guard, and only §1 parses the record — for the newest run's
 * identity and its status tally, the one projection none of the three offered. So this file deliberately does
 * NOT re-assert what those guards already hold; it holds the two things only this script can get wrong.
 *
 * **First: the tally, because it is the reason the script exists.** Its absence is why a maintainer reached for
 * `awk` over the JSONL, and that habit is what produced the reader-side locale bug (`perf-results/README`,
 * "Aggregating one of these files by hand"). A projection nobody can trust is worse than none.
 *
 * **Second: that every section is actually present.** A composition fails silently — drop a delegation and the
 * output is merely shorter, which reads as "nothing to report" rather than as a defect. [everySectionIsPresent]
 * is the case that catches it, and it is the reason the section headers are asserted by name.
 */
class PerfReportScriptTest {

    private val script = Path.of(
        checkNotNull(System.getProperty("perf.report.script")) {
            "run this through :adapter:perfScriptGuardTest, which passes the script's path and declares it as a " +
                "task input; the broad :adapter:test excludes this class for that reason"
        },
    )

    @TempDir
    lateinit var work: Path

    /** A readable record is a report, never a verdict — the whole file rests on this. */
    @Test
    fun aReadableRecordIsNotAFailure() {
        val run = run(record(listOf(10.0, 10.2, 9.9)))

        assertEquals(0, run.status, "a readable record is not a failure: ${run.output}")
    }

    /**
     * The newest run's identity and observation tally — the §1 projection. Asserted on the NEWEST run
     * specifically: reading an older one would look identical on a stable series, which is how a
     * newest-vs-first mistake survives.
     */
    @Test
    fun theStatusSectionNamesTheNewestRunAndTalliesItsObservations() {
        val run = run(record(listOf(10.0, 10.2, 9.9), commits = listOf("older1", "older2", "newest3")))

        assertTrue("newest3" in run.output) { "the NEWEST run's commit, not the first: ${run.output}" }
        assertTrue("older1" !in run.output.substringBefore("2. LEVELS")) {
            "§1 reports one run, not the series — the series is §2's job: ${run.output}"
        }
        assertTrue("3 runs recorded" in run.output) { "the depth of the series is context: ${run.output}" }
        assertTrue("1 accepted" in run.output) { "the status tally is the missing projection: ${run.output}" }
        assertTrue("Macmini9,1 Apple M1" in run.output) {
            "the conditions are what make a number readable: ${run.output}"
        }
    }

    /**
     * A breach is the one line in §1 that IS a verdict, so it must be impossible to miss — and its wording
     * must carry the re-measure instruction, because a contended run and a regression are indistinguishable
     * from a single observation (IF280/IF293).
     */
    @Test
    fun aBreachedObservationIsCalledOutWithItsReMeasureInstruction() {
        val run = run(record(listOf(10.0), status = "breached"))

        assertTrue("ATTENTION" in run.output) { "a breach must not read as ordinary output: ${run.output}" }
        assertTrue("1 breached" in run.output) { "the count is part of the tally: ${run.output}" }
        assertTrue("isolation" in run.output) {
            "a breach without the re-measure instruction invites reading contention as a regression: ${run.output}"
        }
    }

    /**
     * THE CONTROL for the case above. A script that printed the warning unconditionally would satisfy it, and
     * a standing warning is worth exactly as much as no warning.
     */
    @Test
    fun aCleanRecordDoesNotWarn() {
        val run = run(record(listOf(10.0, 10.2, 9.9)))

        assertTrue("ATTENTION" !in run.output) { "nothing breached, so nothing to flag: ${run.output}" }
    }

    /** Every delegation is present — a dropped section makes the report shorter, never louder. */
    @Test
    fun everySectionIsPresent() {
        val run = run(record(listOf(10.0, 10.2, 9.9, 10.1)))

        for (section in listOf("1. STATUS", "2. LEVELS", "3. STEPS", "4. HEADROOM", "5. BARS")) {
            assertTrue(section in run.output) { "section '$section' is missing: ${run.output}" }
        }
    }

    // ── fixtures ──────────────────────────────────────────────────────────────────────────────────────

    private fun record(
        runs: List<Double>,
        commits: List<String>? = null,
        status: String = "accepted",
    ): Path {
        val root = work.resolve("results")
        val dir = root.resolve("darwin-aarch64").also { Files.createDirectories(it) }
        runs.forEachIndexed { i, ms ->
            val stamp = "2026-08-%02dT000000Z".format(i + 1)
            Files.writeString(
                dir.resolve("$stamp.jsonl"),
                """
                {"kind":"run","environment":"darwin-aarch64","capturedAt":"$stamp","commit":"${commits?.get(i) ?: "c%04d".format(i)}","os":"Mac OS X","arch":"aarch64","jdk":"21.0.12","processors":8,"machine":"Macmini9,1 Apple M1","loadAverage":2.0,"ci":false,"runId":null,"runnerName":null}
                {"kind":"observation","environment":"darwin-aarch64","key":"$KEY","scenario":"diagnostic/B1@1 fingerprint=abc","lane":"B1 sub-quadratic check","n":5000,"engine":"interpreter","clock":"cpu","medianMs":$ms,"status":"$status","loadAverage":2.0}
                """.trimIndent() + "\n",
            )
        }
        return root
    }

    private class Run(val status: Int, val output: String)

    private fun run(resultsDir: Path): Run {
        val builder = ProcessBuilder("bash", script.toString())
            .directory(script.parent.parent.toFile())
            .redirectErrorStream(true)
        builder.environment()["PERF_RESULTS_DIR"] = resultsDir.toString()
        val process = builder.start()
        val output = process.inputStream.bufferedReader().readText()
        return Run(process.waitFor(), output)
    }

    private companion object {
        const val KEY = "B1 sub-quadratic check|diagnostic/B1@1 fingerprint=abc|n=5000|interpreter|cpu"
    }
}
