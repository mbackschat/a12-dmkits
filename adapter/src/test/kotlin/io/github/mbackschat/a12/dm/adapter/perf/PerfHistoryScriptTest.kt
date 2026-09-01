package io.github.mbackschat.a12.dm.adapter.perf

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * `scripts/perf-history.sh` — the two properties a *renderer* of the committed record has to hold.
 *
 * The script had no guard at all until 2026-08-12, which is how its label came to drop the scenario: ten
 * distinct `kernel parity` rows of the real record all printed as `kernel parity @1000 [groovy/wall]`, so the
 * table could not say which cost had drifted. The defect was found while building the sibling `perf-trend.sh`,
 * which had inherited the same function.
 *
 * This is deliberately NOT a second copy of `PerfTrendScriptTest`. The detector's thresholds and segmentation are
 * its own; what belongs here is only what this script promises — that a row is identifiable, and that an
 * unreadable record is not rendered as an empty success.
 */
class PerfHistoryScriptTest {

    private val script = Path.of(
        checkNotNull(System.getProperty("perf.history.script")) {
            "run this through :adapter:perfScriptGuardTest, which passes the script's path and declares it as a " +
                "task input; the broad :adapter:test excludes this class for that reason"
        },
    )

    @TempDir
    lateinit var work: Path

    /** Two scenarios of one lane, at one scale and engine, must not render as the same row. */
    @Test
    fun twoScenariosOfOneLaneAreDistinguishableInTheTable() {
        val run = run(recordWithTwoScenarios())

        assertEquals(0, run.status, "a readable record is not a failure: ${run.output}")
        val lines = run.output.lines().filter { "kernel parity" in it }
        assertEquals(2, lines.map { it.trim().substringBefore("  ") }.distinct().size) {
            "the two rows must carry distinct labels, else the table cannot say which cost drifted: ${run.output}"
        }
    }

    /**
     * **The machine belongs in the column header.** This table is the one surface where a human compares absolute
     * numbers across runs, and `scripts/perf-anchor.sh` refuses to fold two machines for the reason that governs
     * here too: an absolute time is a property of the machine, not of the runner class (IF280). The header carried
     * commit, JDK, processors and load — everything except the field that work added.
     */
    @Test
    fun eachRunsHeaderNamesTheMachineItWasMeasuredOn() {
        val run = run(recordWithMachines(APPLE_M1, APPLE_M1))

        assertEquals(0, run.status, run.output)
        val headers = run.output.lines().filter { "commit=" in it }
        assertEquals(2, headers.size, "one header line per run: ${run.output}")
        assertTrue(headers.all { APPLE_M1 in it }) { "every run must state its machine: ${run.output}" }
    }

    /**
     * **A table whose runs span two machines discloses it; it does not split.** The anchor script REFUSES such a
     * fold because it authors a bar, and a wrong fold there is a false verdict. This script renders a report, so the
     * costs run the other way: splitting on `machine` would cut the darwin series in two — 17 of the 28 committed
     * runs predate the field — destroying the continuity a trend table exists for, to guard against a machine change
     * that probably never happened. So the fold stays visible instead of forbidden, which is the same escalation
     * `PerfMarginFloors` and the `[perf][STALE]` reporter already use: say it loudly and let the reader judge.
     */
    @Test
    fun aTableWhoseRunsSpanTwoMachinesSaysSoRatherThanComparingThemSilently() {
        val run = run(recordWithMachines(APPLE_M1, APPLE_M1_PRO))

        assertEquals(0, run.status, run.output)
        val warnings = run.output.lines().filter { MIXED in it }
        assertEquals(1, warnings.size) {
            "a table comparing two machines' absolute numbers must say so exactly once: ${run.output}"
        }
        assertTrue(APPLE_M1 in warnings.single() && APPLE_M1_PRO in warnings.single()) {
            "the warning has to name the machines, else it cannot be acted on: ${warnings.single()}"
        }
    }

    /** The positive control's negative: one machine must not be reported as a mix. */
    @Test
    fun aTableOfOneMachineIsNotWarnedAbout() {
        val run = run(recordWithMachines(APPLE_M1, APPLE_M1))

        assertTrue(run.output.lines().none { MIXED in it }) { "a single machine is not a mix: ${run.output}" }
    }

    /**
     * **A run predating the field is not evidence of a different machine.** 17 of the 28 committed runs carry no
     * `machine`; treating absence as a distinct identity would put a permanent warning on every invocation over the
     * real record, which is how a warning stops being read. Unknown is disclosed, never compared.
     */
    @Test
    fun aRunThatPredatesTheMachineFieldIsNotEvidenceOfADifferentMachine() {
        val run = run(recordWithMachines(null, APPLE_M1))

        assertEquals(0, run.status, run.output)
        assertTrue(run.output.lines().none { MIXED in it }) {
            "an unrecorded machine is unknown, not different: ${run.output}"
        }
        assertTrue(run.output.lines().any { "commit=" in it && "machine=?" in it }) {
            "the run that does not name its machine must say so: ${run.output}"
        }
    }

    /** An unreadable record must fail rather than render as a clean table of nothing. */
    @Test
    fun aRecordWithNoCompleteRunsIsRefused() {
        val root = work.resolve("results")
        Files.createDirectories(root.resolve("darwin-aarch64"))
        Files.writeString(root.resolve("darwin-aarch64").resolve("2026-08-01T000000Z.jsonl"), "not json\n")

        val run = run(root)

        assertNotEquals(0, run.status) {
            "an empty table reads as 'nothing drifted', which is the one conclusion a renderer of an unreadable " +
                "record must not produce: ${run.output}"
        }
    }

    private fun recordWithTwoScenarios(): Path {
        val root = work.resolve("results")
        val dir = root.resolve("darwin-aarch64").also { Files.createDirectories(it) }
        repeat(3) { i ->
            val stamp = "2026-08-0${i + 1}T000000Z"
            Files.writeString(
                dir.resolve("$stamp.jsonl"),
                """
                {"kind":"run","environment":"darwin-aarch64","capturedAt":"$stamp","commit":"c$i","os":"Mac OS X","arch":"x","jdk":"21.0.12","processors":8,"machine":"M","loadAverage":2.0,"ci":false}
                {"kind":"observation","environment":"darwin-aarch64","key":"kernel parity|diagnostic/CO-correlation@1 fingerprint=a|n=1000|groovy|wall","scenario":"diagnostic/CO-correlation@1 fingerprint=a","lane":"kernel parity","n":1000,"engine":"groovy","clock":"wall","medianMs":1${i}.0}
                {"kind":"observation","environment":"darwin-aarch64","key":"kernel parity|diagnostic/MSC-multi-star@1 fingerprint=b|n=1000|groovy|wall","scenario":"diagnostic/MSC-multi-star@1 fingerprint=b","lane":"kernel parity","n":1000,"engine":"groovy","clock":"wall","medianMs":2${i}.0}
                """.trimIndent() + "\n",
            )
        }
        return root
    }

    /** One run per entry, all of one lane and scale, differing only in the machine each was measured on. */
    private fun recordWithMachines(vararg machines: String?): Path {
        val root = work.resolve("results")
        val dir = root.resolve("darwin-aarch64").also { Files.createDirectories(it) }
        machines.forEachIndexed { i, machine ->
            val stamp = "2026-08-0${i + 1}T000000Z"
            val named = machine?.let { ""","machine":"$it"""" } ?: ""
            Files.writeString(
                dir.resolve("$stamp.jsonl"),
                """
                {"kind":"run","environment":"darwin-aarch64","capturedAt":"$stamp","commit":"c$i","os":"Mac OS X","arch":"aarch64","jdk":"21.0.12","processors":8,"loadAverage":2.0,"ci":false$named}
                {"kind":"observation","environment":"darwin-aarch64","key":"kernel parity|diagnostic/CO-correlation@1 fingerprint=a|n=1000|groovy|wall","scenario":"diagnostic/CO-correlation@1 fingerprint=a","lane":"kernel parity","n":1000,"engine":"groovy","clock":"wall","medianMs":1${i}.0}
                """.trimIndent() + "\n",
            )
        }
        return root
    }

    private class Run(val status: Int, val output: String)

    private fun run(resultsDir: Path): Run {
        // The script resolves its record relative to the repo root, so the seam is the argument-free RESULTS path
        // it already honours via its own `results` variable: point it by working directory instead.
        val builder = ProcessBuilder("/bin/bash", script.toString())
            .directory(script.parent.parent.toFile())
            .redirectErrorStream(true)
        builder.environment()["PERF_RESULTS_DIR"] = resultsDir.toString()
        val process = builder.start()
        val output = process.inputStream.bufferedReader().readText()
        return Run(process.waitFor(), output)
    }

    private companion object {
        const val APPLE_M1 = "Macmini9,1 Apple M1"
        const val APPLE_M1_PRO = "MacBookPro18,3 Apple M1 Pro"

        /** The stable part of the mixed-machine disclosure, so the test pins the fact and not the sentence. */
        const val MIXED = "different machines"
    }
}
