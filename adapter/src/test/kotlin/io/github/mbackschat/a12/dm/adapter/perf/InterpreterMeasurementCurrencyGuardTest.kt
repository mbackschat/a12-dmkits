package io.github.mbackschat.a12.dm.adapter.perf

import java.io.File
import java.nio.file.Files
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Interpreter production source may not outrun the last recorded interpreter measurement.
 *
 * **The gap this closes was measured, not imagined.** The portable-consistency plan ran 2026-09-11 → 2026-09-18
 * under NFR #1 — *"the interpreter's performance is never compromised"*, 5 % per row against the restart
 * baseline, and **"missing or inconclusive evidence fails rather than passes"** — and `perf-results/` jumps from
 * `2026-08-24` straight to the end of it. Seven days, one interpreter production commit, zero measurements, and
 * nothing anywhere went red. The numbers turned out fine when finally taken, which is luck rather than process:
 * the same silence would have covered a real regression exactly as well.
 *
 * **Why the existing gates could not catch it.** The release perf gate is real and thorough, but it runs at
 * RELEASE and the plan shipped nothing; and H4's own mechanical discriminator — the interpreter's resolved
 * production set plus its built-artifact digests, which the plan says "decides whether a checker-only edit
 * requires an interpreter sweep" — was described in prose and never built. So the one thing that would have
 * answered "did this touch the interpreter?" cheaply did not exist, and the fallback was a human remembering.
 *
 * **What it asserts, and what it deliberately does not.** It fails when a commit touching interpreter
 * PRODUCTION source is not covered by any recorded measurement. It says nothing about whether the numbers were
 * good — that is the anchor's job, and conflating the two would let a green guard imply a bar it never read.
 * Test-source and documentation commits under `interpreter/src` do not count, because they do not ship.
 */
class InterpreterMeasurementCurrencyGuardTest {

    @Test
    fun everyInterpreterProductionCommitIsCoveredByARecordedMeasurement() {
        val repo = repositoryRoot()
        val unmeasured = InterpreterMeasurementCurrency.unmeasuredCommits(repo)

        assertEquals(
            emptyList<String>(), unmeasured,
            "interpreter production source has moved past every recorded measurement, and NFR #1 says missing " +
                "evidence FAILS rather than passes. Unmeasured commits (newest first):\n  " +
                unmeasured.joinToString("\n  ") +
                "\n\nTake a measurement and commit the record it writes:\n  ./scripts/perf.sh gate\n" +
                "Records land in perf-results/<environment>/ and are committed, never under build/.",
        )
    }

    /**
     * The positive control, and it is the reason this file carries a seam at all.
     *
     * On a clean checkout with the guard satisfied, the row above passes for an implementation that always
     * returns an empty list — it would assert `emptyList() == emptyList()` and prove nothing. So the rule is
     * driven against a fabricated repository whose interpreter production source demonstrably moved past its
     * only record, exactly as `PerfAnchors.headCommitIn` takes a directory for the same reason.
     */
    @Test
    fun theGuardFiresWhenProductionMovesPastTheLastRecord() {
        // Files.createTempDirectory rather than a name built from a clock: this test needs a unique directory,
        // not an instant, and RawClockCensusTest reads the SOURCE — so a clock call here would have to be
        // dispositioned as a measurement in a lane that cannot measure, which it is not.
        val scratch = Files.createTempDirectory("interp-currency").toFile()
        try {
            val measured = InterpreterMeasurementCurrency.fabricateRepositoryWithUnmeasuredChange(scratch)
            assertTrue(measured.isNotEmpty(), "the fixture did not produce a commit, so it controls nothing")

            val unmeasured = InterpreterMeasurementCurrency.unmeasuredCommits(scratch)

            assertTrue(
                unmeasured.isNotEmpty(),
                "the fabricated repository moved interpreter production source past its only record and the " +
                    "guard reported nothing — it cannot fail, so the row above is vacuous",
            )
        } finally {
            scratch.deleteRecursively()
        }
    }

    private fun repositoryRoot(): File {
        var here = File("").absoluteFile
        while (here.parentFile != null) {
            if (File(here, ".git").exists()) return here
            here = here.parentFile
        }
        error("cannot locate the repository root from ${File("").absolutePath}")
    }
}
