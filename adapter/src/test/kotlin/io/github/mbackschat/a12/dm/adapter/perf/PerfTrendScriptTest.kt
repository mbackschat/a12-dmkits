package io.github.mbackschat.a12.dm.adapter.perf

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * `scripts/perf-trend.sh` — IG121 piece 2, **executed** against synthetic records rather than read as source.
 *
 * The script reads the committed series and reports, per row, whether its level has STEPPED and what its
 * run-to-run noise is. Two things make it worth a guard of its own rather than eyeballing its output on the real
 * record: it is the prerequisite piece 1 will build its early-warning band on, so a wrong noise figure here
 * propagates into a gate; and the owner requirement it serves is that **the nightly must never fail with false
 * positives**, which makes [aStableSeriesReportsNoChangePoint] the load-bearing case in this file, not the
 * detection cases.
 *
 * The synthetic-record seam (`PERF_RESULTS_DIR`) is deliberate. Asserting against `perf-results/` would couple
 * the guard to whatever the last real run happened to measure, so every case here builds the exact series it
 * needs — including two that cannot occur on demand in the real record at all (a JDK change mid-series, and the
 * absent `machine` field that 17 committed darwin runs actually carry).
 */
class PerfTrendScriptTest {

    private val script = Path.of(
        checkNotNull(System.getProperty("perf.trend.script")) {
            "run this through :adapter:perfScriptGuardTest, which passes the script's path and declares it as a " +
                "task input; the broad :adapter:test excludes this class for that reason"
        },
    )

    @TempDir
    lateinit var work: Path

    // ── the two controls that decide whether the instrument is worth anything ──────────────────────────

    /**
     * **THE FALSE-POSITIVE CONTROL, and the reason the detection cases below are not enough.** A detector that
     * reported a change point on every row would satisfy every other case in this file. This series wanders
     * ±4 % with no step in it, which is the ordinary run-to-run spread of a fixed machine — it must read as
     * stable, and the noise figure it prints is the quantity piece 1 will size its band from.
     */
    @Test
    fun aStableSeriesReportsNoChangePoint() {
        val dir = record(
            "darwin-aarch64",
            runs = listOf(10.0, 10.4, 9.7, 10.2, 9.9, 10.3, 10.1, 9.8),
        )

        val run = run(dir)

        assertEquals(0, run.status, "a readable record is not a failure: ${run.output}")
        assertTrue("stable" in run.output) { "a wandering series must read as stable: ${run.output}" }
        assertTrue("CHANGE" !in run.output) {
            "no step is present, so any CHANGE here is a false positive — the failure mode this piece exists " +
                "to avoid: ${run.output}"
        }
    }

    /** The positive control: a real step must be found, with its direction and its boundary revision named. */
    @Test
    fun aSteppedSeriesReportsTheChangePointWithItsBoundaryRevision() {
        val dir = record(
            "darwin-aarch64",
            runs = listOf(10.0, 10.2, 9.9, 10.1, 6.0, 6.1, 5.9, 6.05),
            commits = listOf("aaa1", "aaa2", "aaa3", "aaa4", "step5", "aaa6", "aaa7", "aaa8"),
        )

        val run = run(dir)

        assertEquals(0, run.status, "a readable record is not a failure: ${run.output}")
        assertTrue("CHANGE" in run.output) { "a 40% step must be detected: ${run.output}" }
        assertTrue("step5" in run.output) {
            "the change point must name the revision at the boundary — that is how a consumer learns which " +
                "commit re-based the row: ${run.output}"
        }
        assertTrue("-4" in run.output) { "the reported shift must carry its direction and size: ${run.output}" }

        // THE DISCRIMINATOR for which series the noise is taken over, and the one piece 1 depends on. Sized on
        // the runs SINCE the step it is ~1.2%; sized on the whole series the step itself dominates the
        // dispersion and it is ~27%. A band built from the latter would be 20x too wide to ever fire.
        assertTrue(Regex("""noise=±1\.\d%""").containsMatchIn(run.output)) {
            "the noise must be the spread of the CURRENT level, not of a series containing the step: ${run.output}"
        }
        assertTrue(Regex("""current=6\.\d\dms""").containsMatchIn(run.output)) {
            "the current level is the post-step median, not the whole-series median (~7.8): ${run.output}"
        }
    }

    /**
     * **A tiny shift on a very QUIET row is not a change point**, which is the half of the rule the score cannot
     * carry. This series steps +12 % with noise near 0.1 %, so the robust score is ~11 and clears `Z` easily;
     * `MIN_SHIFT` is the only thing that keeps it stable. Without this case, dropping the shift requirement
     * entirely leaves every other test in this file green — measured, not assumed: it is the one mutation of four
     * that survived the first version of this guard.
     *
     * The suppression is a deliberate trade, not an oversight: a 12 % shift on a quiet row *is* real, and
     * reporting it nightly is what makes a flag surface get ignored. `MIN_SHIFT` is the dial.
     */
    @Test
    fun aSmallShiftOnAVeryQuietRowIsNotReportedEvenThoughItsScoreClearsTheThreshold() {
        val dir = record(
            "darwin-aarch64",
            runs = listOf(10.00, 10.01, 9.99, 10.00, 11.20, 11.21, 11.19, 11.20),
        )

        val run = run(dir)

        assertEquals(0, run.status, run.output)
        assertTrue("CHANGE" !in run.output) {
            "a +12% step under the 1.15 practical-shift floor must stay quiet, however clean the signal: " +
                "${run.output}"
        }
        assertTrue("stable" in run.output) { "…and it must be reported as stable, not omitted: ${run.output}" }
    }

    /**
     * **The converse, and the other mutation that survived the first version of this guard.** A NOISY row whose
     * apparent level rises 29 % is ordinary wander: its robust score is 1.9, well under `Z`, so the shift
     * requirement alone would report it. This is the nightly false positive in its most likely real form — a row
     * that swings ±20 % between runs on a shared machine will cross any fixed practical-shift floor eventually.
     *
     * Together with the quiet-row case above, the pair pins that **both** conditions are load-bearing; each was
     * added after measuring that removing its half left the rest of this file green.
     */
    @Test
    fun aLargeShiftOnAVeryNoisyRowIsNotReportedBecauseItsScoreStaysLow() {
        val dir = record(
            "darwin-aarch64",
            runs = listOf(8.0, 12.0, 9.0, 11.0, 10.5, 14.0, 11.0, 13.5),
        )

        val run = run(dir)

        assertEquals(0, run.status, run.output)
        assertTrue("CHANGE" !in run.output) {
            "a +29% apparent shift on a row that swings ±20% between runs is wander, not a step: ${run.output}"
        }
        assertTrue("stable" in run.output) { "…and it is reported as stable: ${run.output}" }
    }

    // ── segmentation: a platform change re-bases every number and is not a regression ─────────────────

    /**
     * A JDK change mid-series shifts the level by 40 %, and that must be reported as **two segments** rather than
     * as a step in one. This is the case the gap names explicitly: a platform change re-bases every number and
     * would otherwise read as a regression.
     */
    @Test
    fun aJdkChangeSegmentsTheSeriesInsteadOfReadingAsAStep() {
        val dir = work.resolve("results").resolve("linux-amd64-ci").also { Files.createDirectories(it) }
        listOf(10.0, 10.2, 9.9).forEachIndexed { i, ms ->
            writeRun(dir, stamp = "2026-08-0${i + 1}T000000Z", jdk = "21.0.11", ms = ms)
        }
        listOf(6.0, 6.1, 5.9).forEachIndexed { i, ms ->
            writeRun(dir, stamp = "2026-08-1${i + 1}T000000Z", jdk = "21.0.12", ms = ms)
        }

        val run = run(work.resolve("results"))

        assertEquals(0, run.status, run.output)
        assertTrue("21.0.11" in run.output && "21.0.12" in run.output) {
            "both JDKs must appear as their own segment headers: ${run.output}"
        }
        assertTrue("CHANGE" !in run.output) {
            "a JDK boundary re-bases the number; calling it a change point is the false positive this " +
                "segmentation exists to prevent: ${run.output}"
        }
    }

    /**
     * **The absent-field trap, which the real record actually carries.** `machine` was added after 17 darwin runs
     * were already committed, so those rows have it as `null`. Treating an absent value as a distinct one would
     * split the local series in two — a false boundary produced by a SCHEMA change rather than a hardware one,
     * and it would silently halve the history every noise estimate is computed from.
     */
    @Test
    fun anAbsentMachineFieldDoesNotSplitTheSeries() {
        val dir = work.resolve("results").resolve("darwin-aarch64").also { Files.createDirectories(it) }
        listOf(10.0, 10.4, 9.7, 10.2).forEachIndexed { i, ms ->
            writeRun(dir, stamp = "2026-08-0${i + 1}T000000Z", machine = null, ms = ms)
        }
        listOf(9.9, 10.3, 10.1, 9.8).forEachIndexed { i, ms ->
            writeRun(dir, stamp = "2026-08-1${i + 1}T000000Z", machine = "Macmini9,1 Apple M1", ms = ms)
        }

        val run = run(work.resolve("results"))

        assertEquals(0, run.status, run.output)
        assertTrue(Regex("""runs=8""").containsMatchIn(run.output)) {
            "all 8 runs belong to one segment: an absent machine is UNKNOWN, not a different machine, and the " +
                "environment has only one known value to place it against: ${run.output}"
        }
    }

    /**
     * **Two rows of the same lane differing only in SCENARIO must render distinguishably.** Found on the real
     * record, where ten distinct `kernel parity` scenarios all rendered as `kernel parity @1000 [groovy/wall]`
     * because the sibling `perf-history.sh` label this was derived from drops the scenario. A report whose
     * purpose is to name the row that changed is worthless when it cannot say which row that is.
     */
    @Test
    fun twoScenariosOfOneLaneDoNotCollapseToTheSameLabel() {
        val dir = work.resolve("results")
        val env = dir.resolve("darwin-aarch64").also { Files.createDirectories(it) }
        repeat(6) { i ->
            val stamp = "2026-08-%02dT000000Z".format(i + 1)
            Files.writeString(
                env.resolve("$stamp.jsonl"),
                """
                {"kind":"run","environment":"darwin-aarch64","capturedAt":"$stamp","commit":"c$i","os":"Mac OS X","arch":"x","jdk":"21.0.12","processors":8,"machine":"M","loadAverage":2.0,"ci":false}
                {"kind":"observation","environment":"darwin-aarch64","key":"kernel parity|diagnostic/CO-correlation@1 fingerprint=a|n=1000|groovy|wall","scenario":"diagnostic/CO-correlation@1 fingerprint=a","lane":"kernel parity","n":1000,"engine":"groovy","clock":"wall","medianMs":10.0}
                {"kind":"observation","environment":"darwin-aarch64","key":"kernel parity|diagnostic/MSC-multi-star@1 fingerprint=b|n=1000|groovy|wall","scenario":"diagnostic/MSC-multi-star@1 fingerprint=b","lane":"kernel parity","n":1000,"engine":"groovy","clock":"wall","medianMs":20.0}
                """.trimIndent() + "\n",
            )
        }

        val run = run(dir)

        assertEquals(0, run.status, run.output)
        assertTrue("CO-correlation" in run.output && "MSC-multi-star" in run.output) {
            "each row must carry its scenario, else the two are indistinguishable: ${run.output}"
        }
    }

    // ── the --ratio view: a margin's noise is not its operands' noise ──────────────────────────────────

    /**
     * **THE DISCRIMINATOR for the whole ratio view.** Both engines swing 2x together across runs while their
     * ratio stays fixed at 4.00x — because they share a JVM, vCPUs and load slice, so an excursion largely
     * cancels in the ratio. A view that sized a cross-engine band from the operands' spread would read ±40 % of
     * noise here; the ratio's own spread is zero, and the report must say so (the ±1.0 % floor).
     *
     * This is the measured reason piece 1's band belongs on the ratio series rather than on the medians piece 2
     * already reports, and the reason the ratio series has to be *derived* — it is not a key in the record.
     */
    @Test
    fun theMarginSpreadIsTheRatiosOwnAndNotItsOperands() {
        val dir = work.resolve("results")
        val env = dir.resolve("darwin-aarch64").also { Files.createDirectories(it) }
        // interpreter and kernel move together: 5/20, 10/40, 7.5/30 — ratio constant, absolutes span 2x.
        listOf(5.0 to 20.0, 10.0 to 40.0, 7.5 to 30.0, 5.0 to 20.0, 10.0 to 40.0, 7.5 to 30.0)
            .forEachIndexed { i, (interp, kernel) ->
                val stamp = "2026-08-%02dT000000Z".format(i + 1)
                Files.writeString(
                    env.resolve("$stamp.jsonl"),
                    """
                    {"kind":"run","environment":"darwin-aarch64","capturedAt":"$stamp","commit":"c$i","os":"Mac OS X","arch":"x","jdk":"21.0.12","processors":8,"machine":"M","loadAverage":2.0,"ci":false}
                    {"kind":"observation","environment":"darwin-aarch64","key":"kernel parity|diagnostic/CO@1 fingerprint=a|n=200|interpreter|wall","scenario":"diagnostic/CO@1 fingerprint=a","lane":"kernel parity","n":200,"engine":"interpreter","clock":"wall","medianMs":$interp}
                    {"kind":"observation","environment":"darwin-aarch64","key":"kernel parity|diagnostic/CO@1 fingerprint=a|n=200|java|wall","scenario":"diagnostic/CO@1 fingerprint=a","lane":"kernel parity","n":200,"engine":"java","clock":"wall","medianMs":$kernel}
                    """.trimIndent() + "\n",
                )
            }

        val run = run(dir, "--ratio")

        assertEquals(0, run.status, run.output)
        assertTrue(Regex("""margin=\s*4\.00x""").containsMatchIn(run.output)) {
            "the margin is kernel/interpreter = 4.00x: ${run.output}"
        }
        assertTrue(Regex("""spread=±\s*1\.0%""").containsMatchIn(run.output)) {
            "a constant ratio has no spread, however much its operands moved — sizing a band from the operands " +
                "would have read ±40% here: ${run.output}"
        }
        assertTrue(Regex("""worst=\s*4\.00x""").containsMatchIn(run.output)) {
            "the worst margin equals the constant one: ${run.output}"
        }
    }

    /**
     * **THE BACKTEST'S LOAD-BEARING RESTRICTION, and the bug the first version shipped.** A candidate band is
     * counted against the row's CURRENT level only. This series steps from a 2x margin to a 6x one — an
     * optimization — so a band at half the current median (3x) sits above every pre-step margin. Counting those
     * would report 4 firings that are not regressions at all but the row's older, slower level, which is exactly
     * what a whole-series backtest did: at 0.40x it reported 28 firings across the real record, all of them
     * pre-optimization margins. The gap named this trap ("segmented by revision, not only by platform") before it
     * was hit.
     */
    @Test
    fun theBandIsBacktestedAgainstTheCurrentLevelOnlyNotAcrossAStep() {
        val dir = work.resolve("results")
        val env = dir.resolve("darwin-aarch64").also { Files.createDirectories(it) }
        // margin = kernel/interp: 2x for four runs, then 6x for four — a step up, i.e. an optimization.
        listOf(2.0, 2.0, 2.05, 1.95, 6.0, 6.1, 5.9, 6.0).forEachIndexed { i, margin ->
            val stamp = "2026-08-%02dT000000Z".format(i + 1)
            Files.writeString(
                env.resolve("$stamp.jsonl"),
                """
                {"kind":"run","environment":"darwin-aarch64","capturedAt":"$stamp","commit":"c$i","os":"Mac OS X","arch":"x","jdk":"21.0.12","processors":8,"machine":"M","loadAverage":2.0,"ci":false}
                {"kind":"observation","environment":"darwin-aarch64","key":"kernel parity|diagnostic/CO@1 fingerprint=a|n=200|interpreter|wall","scenario":"diagnostic/CO@1 fingerprint=a","lane":"kernel parity","n":200,"engine":"interpreter","clock":"wall","medianMs":1.0}
                {"kind":"observation","environment":"darwin-aarch64","key":"kernel parity|diagnostic/CO@1 fingerprint=a|n=200|java|wall","scenario":"diagnostic/CO@1 fingerprint=a","lane":"kernel parity","n":200,"engine":"java","clock":"wall","medianMs":$margin}
                """.trimIndent() + "\n",
            )
        }

        val run = run(dir, "--ratio", env = mapOf("BAND" to "0.50"))

        assertEquals(0, run.status, run.output)
        assertTrue("would have fired 0 time(s)" in run.output) {
            "the four pre-step 2x margins sit below half the current 6x median, but they are the row's older " +
                "level and not regressions — counting them is the false-positive engine this restriction " +
                "removes: ${run.output}"
        }
        assertTrue(Regex("""runs=4/8""").containsMatchIn(run.output)) {
            "the row must report that it is judging 4 of its 8 runs, so a reader can see the step was excluded: " +
                "${run.output}"
        }
    }

    /**
     * **`BAND_SIGMA` scales with the row's own spread; `BAND` does not — which is the whole reason the second
     * formulation exists.** Two rows share a median margin of 10x and differ only in noise: one wanders ±2 %, the
     * other ±30 %. A fraction of the median gives them the SAME floor, so it is simultaneously too loose for the
     * quiet row (unguarded until it halves) and too tight for the noisy one (fires on ordinary wander — measured:
     * the only rows a 0.50x fraction tripped on the real record were the two noisiest). Expressed in robust
     * deviations, the quiet row's floor sits just under its median and the noisy row's far below it.
     */
    @Test
    fun theSigmaBandTightensOnAQuietRowAndLoosensOnANoisyOneUnlikeAFraction() {
        val dir = work.resolve("results")
        val env = dir.resolve("darwin-aarch64").also { Files.createDirectories(it) }
        val quiet = listOf(10.0, 10.2, 9.8, 10.1, 9.9, 10.0)      // ±2%
        val noisy = listOf(7.0, 13.0, 8.0, 12.5, 10.0, 10.0)      // ±30%, same median
        quiet.indices.forEach { i ->
            val stamp = "2026-08-%02dT000000Z".format(i + 1)
            Files.writeString(
                env.resolve("$stamp.jsonl"),
                """
                {"kind":"run","environment":"darwin-aarch64","capturedAt":"$stamp","commit":"c$i","os":"Mac OS X","arch":"x","jdk":"21.0.12","processors":8,"machine":"M","loadAverage":2.0,"ci":false}
                {"kind":"observation","environment":"darwin-aarch64","key":"kernel parity|diagnostic/QUIET@1 fingerprint=a|n=100|interpreter|wall","scenario":"diagnostic/QUIET@1 fingerprint=a","lane":"kernel parity","n":100,"engine":"interpreter","clock":"wall","medianMs":1.0}
                {"kind":"observation","environment":"darwin-aarch64","key":"kernel parity|diagnostic/QUIET@1 fingerprint=a|n=100|java|wall","scenario":"diagnostic/QUIET@1 fingerprint=a","lane":"kernel parity","n":100,"engine":"java","clock":"wall","medianMs":${quiet[i]}}
                {"kind":"observation","environment":"darwin-aarch64","key":"kernel parity|diagnostic/NOISY@1 fingerprint=b|n=100|interpreter|wall","scenario":"diagnostic/NOISY@1 fingerprint=b","lane":"kernel parity","n":100,"engine":"interpreter","clock":"wall","medianMs":1.0}
                {"kind":"observation","environment":"darwin-aarch64","key":"kernel parity|diagnostic/NOISY@1 fingerprint=b|n=100|java|wall","scenario":"diagnostic/NOISY@1 fingerprint=b","lane":"kernel parity","n":100,"engine":"java","clock":"wall","medianMs":${noisy[i]}}
                """.trimIndent() + "\n",
            )
        }

        val sigma = run(dir, "--ratio", env = mapOf("BAND_SIGMA" to "3"))
        assertEquals(0, sigma.status, sigma.output)
        val quietBand = bandOf(sigma.output, "QUIET")
        val noisyBand = bandOf(sigma.output, "NOISY")
        assertTrue(quietBand > noisyBand * 1.5) {
            "at one sigma multiple the quiet row's floor must sit far above the noisy row's, though their medians " +
                "match — otherwise the band is not reading the spread at all: quiet=$quietBand noisy=$noisyBand\n" +
                sigma.output
        }

        val fraction = run(dir, "--ratio", env = mapOf("BAND" to "0.50"))
        assertEquals(bandOf(fraction.output, "QUIET"), bandOf(fraction.output, "NOISY"), 0.05) {
            "the control: a fraction of the median gives two equally-noisy-looking rows the SAME floor, which is " +
                "what makes it the wrong shape here\n" + fraction.output
        }
    }

    /** The `band=` figure printed for the row whose label contains [marker]. */
    private fun bandOf(output: String, marker: String): Double {
        val line = output.lines().first { marker in it && "band=" in it }
        return Regex("""band=\s*([\d.]+)x""").find(line)!!.groupValues[1].toDouble()
    }

    /** A row with no kernel arm has no margin, and must be omitted rather than counted as parity. */
    @Test
    fun aRowWithoutAKernelArmIsNotReportedAsAMargin() {
        val dir = record("darwin-aarch64", runs = listOf(10.0, 10.4, 9.7, 10.2, 9.9, 10.3))

        val run = run(dir, "--ratio")

        assertNotEquals(0, run.status) {
            "the fixture's only row is interpreter-only, so there is no margin to report; inventing one (or " +
                "printing an empty table as success) is how a missing kernel arm reads as parity: ${run.output}"
        }
    }

    // ── refusals: the instrument must never report an empty record as a clean result ───────────────────

    @Test
    fun aRecordDirectoryThatDoesNotExistIsRefused() {
        val run = run(work.resolve("absent"))

        assertNotEquals(0, run.status, "a missing record cannot be a clean report of nothing")
        assertTrue("no " in run.output.lowercase()) { "the refusal must name what is missing: ${run.output}" }
    }

    @Test
    fun aRecordWithNoCompleteRunsIsRefusedRatherThanReportedAsStable() {
        val dir = work.resolve("results").resolve("darwin-aarch64").also { Files.createDirectories(it) }
        Files.writeString(dir.resolve("2026-08-01T000000Z.jsonl"), "not json at all\n")

        val run = run(work.resolve("results"))

        assertNotEquals(0, run.status) {
            "an unreadable record reported as success would read as 'no regressions', the one conclusion the " +
                "instrument must never invent: ${run.output}"
        }
    }

    /** Too short a series cannot support a step claim, and must say so rather than invent one. */
    @Test
    fun aSeriesTooShortForAStepClaimSaysSoInsteadOfGuessing() {
        val dir = record("darwin-aarch64", runs = listOf(10.0, 6.0))

        val run = run(dir)

        assertEquals(0, run.status, run.output)
        assertTrue("insufficient" in run.output) {
            "two points with a 40% gap are not evidence of a step; the honest answer is that the series is too " +
                "short: ${run.output}"
        }
        assertTrue("CHANGE" !in run.output) { "…and no change point may be claimed from it: ${run.output}" }
    }

    // ── helpers ───────────────────────────────────────────────────────────────────────────────────────

    /** A one-environment record whose single row takes the given medians, one run each, oldest first. */
    // ── the cross-arm reading (IG137 piece c) ─────────────────────────────────────────────────────────

    /**
     * **The reason this reading exists.** Each engine arm of a row is decided independently, which leaves the
     * reader to correlate them by eye — and the correlation is the whole signal. All three engines share the
     * JVM, the vCPUs and the load slice within a run, so nothing about our code can move them together: an
     * all-arm step is the machine re-basing, and reading it as a regression is how a contended series gets
     * chased.
     */
    @Test
    fun armsThatAllStepTheSameWayReadAsTheMachineRebasing() {
        val dir = multiEngineRecord(
            interpreter = listOf(10.0, 10.1, 9.9, 10.0, 6.0, 6.1, 5.9, 6.0),
            groovy = listOf(40.0, 40.4, 39.6, 40.0, 24.0, 24.4, 23.6, 24.0),
            java = listOf(30.0, 30.3, 29.7, 30.0, 18.0, 18.3, 17.7, 18.0),
        )

        val run = run(dir)

        assertEquals(0, run.status, run.output)
        assertTrue("MACHINE re-basing" in run.output) {
            "every arm stepped the same way — no code change moves three engines together: ${run.output}"
        }
    }

    /** The interesting one: only OUR arm moved, so the step is attributable rather than environmental. */
    @Test
    fun armsThatDisagreeAreAttributedToTheSideThatMoved() {
        val dir = multiEngineRecord(
            interpreter = listOf(10.0, 10.1, 9.9, 10.0, 6.0, 6.1, 5.9, 6.0),
            groovy = listOf(40.0, 40.4, 39.6, 40.0, 40.2, 39.8, 40.1, 39.9),
            java = listOf(30.0, 30.3, 29.7, 30.0, 30.1, 29.9, 30.2, 29.8),
        )

        val run = run(dir)

        assertTrue("DISAGREE" in run.output) {
            "the kernel arms held while ours moved — that is not an environment shift: ${run.output}"
        }
        assertTrue("MACHINE re-basing" !in run.output) { "no arm agreement to report: ${run.output}" }
    }

    /**
     * All arms stepped, in OPPOSITE directions. Without this case the "every arm stepped" branch would call it
     * a machine re-base, which is the one reading the data rules out: an environment shift cannot move one
     * engine down while moving another up.
     */
    @Test
    fun armsSteppingOppositeWaysAreNotAnEnvironmentShift() {
        val dir = multiEngineRecord(
            interpreter = listOf(10.0, 10.1, 9.9, 10.0, 6.0, 6.1, 5.9, 6.0),
            groovy = listOf(40.0, 40.4, 39.6, 40.0, 64.0, 64.4, 63.6, 64.0),
            java = listOf(30.0, 30.3, 29.7, 30.0, 48.0, 48.3, 47.7, 48.0),
        )

        val run = run(dir)

        assertTrue("NOT the same way" in run.output) { "one arm down, two up: ${run.output}" }
        assertTrue("MACHINE re-basing" !in run.output) {
            "opposite directions rule out the one reading this branch exists to prevent: ${run.output}"
        }
    }

    /** THE CONTROL: a row where nothing stepped emits no cross-arm line at all — silence, not a verdict. */
    @Test
    fun aStableRowReportsNoCrossArmReading() {
        val dir = multiEngineRecord(
            interpreter = listOf(10.0, 10.2, 9.8, 10.1, 9.9, 10.3, 10.0, 9.7),
            groovy = listOf(40.0, 40.6, 39.4, 40.2, 39.8, 40.5, 40.0, 39.6),
            java = listOf(30.0, 30.4, 29.6, 30.2, 29.8, 30.3, 30.0, 29.7),
        )

        val run = run(dir)

        assertTrue("ARMS" !in run.output) {
            "nothing stepped, so there is nothing to reconcile: ${run.output}"
        }
    }

    /** One run per index carrying all three engine arms of the SAME (lane, scenario, n, clock) row. */
    private fun multiEngineRecord(
        interpreter: List<Double>,
        groovy: List<Double>,
        java: List<Double>,
    ): Path {
        val root = work.resolve("results")
        val dir = root.resolve("darwin-aarch64").also { Files.createDirectories(it) }
        interpreter.indices.forEach { i ->
            val stamp = "2026-08-%02dT000000Z".format(i + 1)
            val observations = listOf("interpreter" to interpreter[i], "groovy" to groovy[i], "java" to java[i])
                .joinToString("\n") { (engine, ms) ->
                    """{"kind":"observation","environment":"darwin-aarch64","key":"kernel parity|diagnostic/X@1 fingerprint=abc|n=250|$engine|wall","scenario":"diagnostic/X@1 fingerprint=abc","lane":"kernel parity","n":250,"engine":"$engine","clock":"wall","medianMs":$ms,"loadAverage":2.0}"""
                }
            Files.writeString(
                dir.resolve("$stamp.jsonl"),
                """{"kind":"run","environment":"darwin-aarch64","capturedAt":"$stamp","commit":"${"c%04d".format(i)}","os":"Mac OS X","arch":"x","jdk":"21.0.12","processors":8,"machine":"Macmini9,1 Apple M1","loadAverage":2.0,"ci":false,"runId":null,"runnerName":null}""" + "\n" + observations + "\n",
            )
        }
        return root
    }

    private fun record(env: String, runs: List<Double>, commits: List<String>? = null): Path {
        val root = work.resolve("results")
        val dir = root.resolve(env).also { Files.createDirectories(it) }
        runs.forEachIndexed { i, ms ->
            writeRun(
                dir,
                stamp = "2026-08-%02dT000000Z".format(i + 1),
                ms = ms,
                commit = commits?.get(i) ?: "c%04d".format(i),
            )
        }
        return root
    }

    private fun writeRun(
        dir: Path,
        stamp: String,
        ms: Double,
        commit: String = "c0000",
        jdk: String = "21.0.12",
        machine: String? = "Macmini9,1 Apple M1",
    ) {
        val env = dir.fileName.toString()
        val os = if (env.startsWith("darwin")) "Mac OS X" else "Linux"
        val machineJson = machine?.let { "\"$it\"" } ?: "null"
        Files.writeString(
            dir.resolve("$stamp.jsonl"),
            """
            {"kind":"run","environment":"$env","capturedAt":"$stamp","commit":"$commit","os":"$os","arch":"x","jdk":"$jdk","processors":8,"machine":$machineJson,"loadAverage":2.0,"ci":false,"runId":null,"runnerName":null}
            {"kind":"observation","environment":"$env","key":"$KEY","scenario":"diagnostic/B1@1 fingerprint=abc","lane":"B1 sub-quadratic check","n":5000,"engine":"interpreter","clock":"cpu","medianMs":$ms,"loadAverage":2.0}
            """.trimIndent() + "\n",
        )
    }

    private class Run(val status: Int, val output: String)

    private fun run(resultsDir: Path, vararg args: String, env: Map<String, String> = emptyMap()): Run {
        // args go BEFORE the pattern, since --ratio is consumed by a leading shift.
        val builder = ProcessBuilder(listOf("bash", script.toString()) + args)
            .directory(script.parent.parent.toFile())
            .redirectErrorStream(true)
        builder.environment()["PERF_RESULTS_DIR"] = resultsDir.toString()
        builder.environment().putAll(env)
        val process = builder.start()
        val output = process.inputStream.bufferedReader().readText()
        return Run(process.waitFor(), output)
    }

    private companion object {
        const val KEY =
            "B1 sub-quadratic check|diagnostic/B1@1 fingerprint=abc|n=5000|interpreter|cpu"
    }
}
