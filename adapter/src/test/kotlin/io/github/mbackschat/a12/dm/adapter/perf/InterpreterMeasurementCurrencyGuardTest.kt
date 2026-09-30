package io.github.mbackschat.a12.dm.adapter.perf

import java.io.File
import java.nio.file.Files
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
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
 *
 * **It also fails when it cannot DECIDE, and that arm is separate because conflating it shipped a false
 * accusation (2026-09-21).** On a shallow checkout no record's commit resolves, and the rule used to answer by
 * reporting the production history — which in a `--depth 1` clone is the single available commit. CI therefore
 * named `9c86459b3`, a commit touching only `settings.gradle` and a doc. Both arms are red, as NFR #1 requires;
 * only one of them is entitled to name a commit.
 */
class InterpreterMeasurementCurrencyGuardTest {

    @Test
    fun everyInterpreterProductionCommitIsCoveredByARecordedMeasurement() {
        when (val currency = InterpreterMeasurementCurrency.currency(repositoryRoot())) {
            is InterpreterMeasurementCurrency.Currency.Current -> Unit
            is InterpreterMeasurementCurrency.Currency.Unmeasured -> fail(
                "interpreter production source has moved past every recorded measurement, and NFR #1 says " +
                    "missing evidence FAILS rather than passes. Unmeasured commits (newest first):\n  " +
                    currency.commits.joinToString("\n  ") +
                    "\n\nTake a measurement and commit the record it writes:\n  ./scripts/perf.sh gate\n" +
                    "Records land in perf-results/<environment>/ and are committed, never under build/.",
            )
            is InterpreterMeasurementCurrency.Currency.Undecidable -> fail(
                "measurement currency could not be decided in this checkout, so NFR #1's refusal of " +
                    "inconclusive evidence applies — but NOT to any commit, because none was examined.\n  " +
                    currency.reason,
            )
        }
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

            val currency = InterpreterMeasurementCurrency.currency(scratch)

            assertInstanceOf(
                InterpreterMeasurementCurrency.Currency.Unmeasured::class.java, currency,
                "the fabricated repository moved interpreter production source past its only record and the " +
                    "guard did not report it — it cannot fail, so the row above is vacuous",
            )
        } finally {
            scratch.deleteRecursively()
        }
    }

    /**
     * The control for the defect that shipped: a SHALLOW checkout must not be read as an unmeasured one.
     *
     * The fixture is current and its HEAD touches no production path, so there is exactly one right answer and
     * two wrong ones. Cloned in full it is [Currency.Current]; cloned at `--depth 1` the records' commits stop
     * resolving, and the old rule answered by reporting the whole production history — which a shallow clone
     * collapses to its single commit, naming a commit that changed nothing this guard watches. Asserting
     * [Currency.Undecidable] rather than merely "not Unmeasured" is deliberate: a pass would also satisfy the
     * weaker claim, and NFR #1 refuses inconclusive evidence.
     */
    @Test
    fun aShallowCheckoutIsUndecidableRatherThanUnmeasured() {
        val scratch = Files.createTempDirectory("interp-currency-shallow").toFile()
        try {
            val origin = File(scratch, "origin")
            origin.mkdirs()
            InterpreterMeasurementCurrency.fabricateCurrentRepositoryWithNonProductionHead(origin)

            assertInstanceOf(
                InterpreterMeasurementCurrency.Currency.Current::class.java,
                InterpreterMeasurementCurrency.currency(origin),
                "the fixture is not current when its history IS readable, so it cannot isolate the depth",
            )

            val shallow = File(scratch, "shallow")
            assertTrue(
                InterpreterMeasurementCurrency.shallowClone(origin, shallow),
                "could not create the shallow clone, so this control proves nothing",
            )

            assertInstanceOf(
                InterpreterMeasurementCurrency.Currency.Undecidable::class.java,
                InterpreterMeasurementCurrency.currency(shallow),
                "a shallow checkout was not reported as undecidable — the shipped defect reported its single " +
                    "commit as unmeasured instead, which accuses a commit the guard never examined",
            )
        } finally {
            scratch.deleteRecursively()
        }
    }

    @Test
    fun sharedProductionSourceInvalidatesMeasurementButTestSourceDoesNot() {
        val scratch = Files.createTempDirectory("interp-currency-shared").toFile()
        try {
            InterpreterMeasurementCurrency.fabricateCurrentRepositoryWithNonProductionHead(scratch)
            fun commitFile(path: String): String {
                File(scratch, path).apply { parentFile.mkdirs(); writeText("val changed = 1\n") }
                fun git(vararg args: String): String {
                    val process = ProcessBuilder(listOf("git") + args).directory(scratch).redirectErrorStream(true).start()
                    val output = process.inputStream.bufferedReader().readText().trim()
                    assertEquals(0, process.waitFor(), output)
                    return output
                }
                git("add", "-A")
                git("commit", "--quiet", "-m", "change fixture source")
                return git("rev-parse", "--short", "HEAD")
            }
            commitFile("shared-regex/src/commonTest/kotlin/Control.kt")
            assertEquals(InterpreterMeasurementCurrency.Currency.Current, InterpreterMeasurementCurrency.currency(scratch))
            val changed = commitFile("shared-regex/src/commonMain/kotlin/Engine.kt")
            assertEquals(InterpreterMeasurementCurrency.Currency.Unmeasured(listOf(changed)),
                InterpreterMeasurementCurrency.currency(scratch))
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
