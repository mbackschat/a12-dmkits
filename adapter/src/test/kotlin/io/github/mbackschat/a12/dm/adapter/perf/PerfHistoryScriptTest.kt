package io.github.mbackschat.a12.dm.adapter.perf

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
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

    private class Run(val status: Int, val output: String)

    private fun run(resultsDir: Path): Run {
        // The script resolves its record relative to the repo root, so the seam is the argument-free RESULTS path
        // it already honours via its own `results` variable: point it by working directory instead.
        val builder = ProcessBuilder("bash", script.toString())
            .directory(script.parent.parent.toFile())
            .redirectErrorStream(true)
        builder.environment()["PERF_RESULTS_DIR"] = resultsDir.toString()
        val process = builder.start()
        val output = process.inputStream.bufferedReader().readText()
        return Run(process.waitFor(), output)
    }
}
