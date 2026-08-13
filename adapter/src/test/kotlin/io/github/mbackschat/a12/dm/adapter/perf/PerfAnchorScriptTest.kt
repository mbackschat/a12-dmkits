package io.github.mbackschat.a12.dm.adapter.perf

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * The refusals of `scripts/perf-anchor.sh`, **executed** rather than read as source text — the anchor is the only bar
 * in this estate that can see a uniform regression (IF280), and every way the refresh can silently take it away is a
 * way for that bar to disappear.
 *
 * The script writes to the committed anchor, so it exposes `ANCHOR_FILE` for exactly this: each case drives the real
 * script against a temporary two-key anchor and asserts its exit status and its diff.
 *
 * The sharp case is a **new** runner class, which is how a hosted block is authored: from a nightly artifact uploaded
 * `if: always()` — so the run most likely to be fed here is one where a
 * growth floor breached, which throws before its lane records an observation. A new block has no previous keys to
 * compare against, so the "would delete an anchor" refusal cannot see the hole; the keys the *other* anchored classes
 * carry are what makes it visible, and they are comparable because a key names the lane, scenario fingerprint, scale,
 * engine and clock, never the machine.
 */
class PerfAnchorScriptTest {

    private val script = Path.of(
        checkNotNull(System.getProperty("perf.anchor.script")) {
            "run this through :adapter:perfScriptGuardTest, which passes the script's path and declares it as a task " +
                "input; the broad :adapter:test excludes this class for that reason"
        },
    )

    private val laneA = "A|scenario-a@1|n=5000|interpreter|cpu"
    private val laneB = "B|scenario-b@1|n=5000|interpreter|cpu"

    @TempDir
    lateinit var work: Path

    @Test
    fun aRunThatDoesNotMeasureEveryAnchoredLaneCannotAuthorANewRunnerClass() {
        val anchor = anchorCarrying(laneA to 1.0, laneB to 2.0)

        val run = refresh(anchor, observations("hosted.jsonl", laneA to 2.4))

        assertNotEquals(0, run.status,
            "a partial run must not author a block: the missing lane would carry no bar at all, and under " +
                "-Dperf.anchor.required=true the nightly reports it as UNANCHORED for as long as nobody notices")
        assertTrue(laneB in run.stderr) { "the refusal must name the lanes that were not measured: ${run.stderr}" }
        assertEquals(setOf("darwin-aarch64"), blocksIn(anchor), "a refused refresh must write nothing")
    }

    /**
     * Two anchored classes legitimately carry different key sets for a while: a scenario's fingerprint is part of
     * its key, so a content change re-keys every lane and the classes are re-measured one at a time. Holding an
     * EXISTING block to the union refuses the first refresh of either one for carrying the other's stale keys,
     * which makes `ALLOW_REMOVALS` the routine way to run the script — and an override nobody can avoid is not a
     * refusal at all.
     */
    @Test
    fun anExistingBlockIsNotHeldToAnotherClassesDivergentLanes() {
        val anchor = anchorWithBlocks(
            "darwin-aarch64" to listOf(laneA to 1.0, laneB to 2.0),
            "linux-amd64-ci" to listOf(laneA to 3.0),   // this class never carried laneB
        )

        val run = refresh(anchor, observations("hosted.jsonl", laneA to 3.4))

        assertEquals(0, run.status,
            "refreshing a block must not require lanes only ANOTHER class carries: ${run.stderr}")
        assertEquals(mapOf(laneA to 3.4), observationsOf(anchor, "linux-amd64-ci"))
        assertEquals(mapOf(laneA to 1.0, laneB to 2.0), observationsOf(anchor, "darwin-aarch64"),
            "the other class must be untouched")
    }

    /** The positive control for the case above: the refusal must survive for the block's OWN lanes. */
    @Test
    fun aRunThatDropsALaneItsOwnBlockCarriesIsStillRefused() {
        val anchor = anchorWithBlocks("linux-amd64-ci" to listOf(laneA to 3.0, laneB to 4.0))

        val run = refresh(anchor, observations("hosted.jsonl", laneA to 3.4))

        assertNotEquals(0, run.status,
            "dropping a lane THIS block anchors is a real removal and must still be refused")
        assertTrue(laneB in run.stderr) { "the refusal must name the dropped lane: ${run.stderr}" }
        assertEquals(mapOf(laneA to 3.0, laneB to 4.0), observationsOf(anchor, "linux-amd64-ci"),
            "a refused refresh must write nothing")
    }

    /** The documented escape hatch, for when a lane is genuinely gone rather than merely unmeasured. */
    @Test
    fun theRefusalIsOverridableSoARemovedLaneCanStillBeRefreshedAway() {
        val anchor = anchorCarrying(laneA to 1.0, laneB to 2.0)

        val run = refresh(anchor, observations("hosted.jsonl", laneA to 2.4), allowRemovals = true)

        assertEquals(0, run.status, "ALLOW_REMOVALS=1 must still author the block: ${run.stderr}")
        assertEquals(mapOf(laneA to 2.4), observationsOf(anchor, "linux-amd64-ci"))
    }

    @Test
    fun aCompleteRunAuthorsTheNewClassAtTheMedianWithoutTouchingTheAnchoredOne() {
        val anchor = anchorCarrying(laneA to 1.0, laneB to 2.0)
        val before = observationsOf(anchor, "darwin-aarch64")

        val run = refresh(
            anchor,
            observations("first.jsonl", laneA to 2.0, laneB to 4.0),
            observations("second.jsonl", laneA to 3.0, laneB to 5.0),
        )

        assertEquals(0, run.status, "a complete run must author the block: ${run.stderr}")
        assertEquals(setOf("darwin-aarch64", "linux-amd64-ci"), blocksIn(anchor))
        assertEquals(before, observationsOf(anchor, "darwin-aarch64"),
            "authoring one runner class must never move another's numbers — they are independent machines")
        assertEquals(mapOf(laneA to 2.5, laneB to 4.5), observationsOf(anchor, "linux-amd64-ci"),
            "with several runs the anchor is their median, which is what made the tolerance tightenable (IF280)")
    }

    /** Absolute times are only comparable within a runner class, so a mixed fold would average two machines. */
    @Test
    fun inputsFromDifferentRunnerClassesAreRefused() {
        val anchor = anchorCarrying(laneA to 1.0, laneB to 2.0)

        val run = refresh(
            anchor,
            observations("hosted.jsonl", laneA to 2.0, laneB to 4.0),
            observations("local.jsonl", laneA to 1.0, laneB to 2.0, environment = "darwin-aarch64"),
        )

        assertNotEquals(0, run.status, "folding two runner classes into one anchor must be refused")
        assertEquals(setOf("darwin-aarch64"), blocksIn(anchor), "a refused refresh must write nothing")
    }

    /**
     * A block that does not name its machine is one the gated lane refuses to assert against at all
     * ([MachineIdentity.check]), so writing one is another way for the bar to disappear — the same class as dropping
     * a lane, one level up.
     */
    @Test
    fun aRefreshRecordsTheMachineTheRunWasMeasuredOn() {
        val anchor = anchorCarrying(laneA to 1.0, laneB to 2.0)

        val run = refresh(anchor, observations("hosted.jsonl", laneA to 2.0, laneB to 4.0, machine = "AMD EPYC 7763"))

        assertEquals(0, run.status, run.stderr)
        assertEquals("AMD EPYC 7763", machineOf(anchor, "linux-amd64-ci"))
    }

    @Test
    fun aRunThatDoesNotNameItsMachineCannotAuthorABlock() {
        val anchor = anchorCarrying(laneA to 1.0, laneB to 2.0)

        val run = refresh(anchor, observations("hosted.jsonl", laneA to 2.0, laneB to 4.0, machine = null))

        assertNotEquals(0, run.status,
            "a block with no machine is one the gated lane will not assert against, so it is a bar that never fires")
        assertTrue("machine" in run.stderr) { "the refusal must say what is missing: ${run.stderr}" }
        assertEquals(setOf("darwin-aarch64"), blocksIn(anchor), "a refused refresh must write nothing")
    }

    /**
     * **The probe's own failure sentinel is not an identity either.** [MachineIdentity.UNKNOWN_MODEL] is the literal
     * `"unknown"` the probe emits when it cannot name the machine, and a truthiness test on the field passes it
     * straight through. The block it authors is worse than one with no machine at all: `MachineIdentity.check`
     * falls to its *mismatch* arm, so the lane never asserts **and** reports "captured on unknown ×N and this run
     * measured on …", which reads as a machine swap rather than as a block that was authored wrong.
     */
    @Test
    fun aRunWhoseMachineProbeFailedCannotAuthorABlockEither() {
        val anchor = anchorCarrying(laneA to 1.0, laneB to 2.0)

        val run = refresh(
            anchor,
            observations("hosted.jsonl", laneA to 2.0, laneB to 4.0, machine = MachineIdentity.UNKNOWN_MODEL),
        )

        assertNotEquals(0, run.status,
            "a block whose machine is the probe's failure sentinel is refused by the gated lane forever, so " +
                "authoring one is the never-fires block this refusal exists to prevent")
        assertTrue("machine" in run.stderr) { "the refusal must say what is unusable: ${run.stderr}" }
        assertEquals(setOf("darwin-aarch64"), blocksIn(anchor), "a refused refresh must write nothing")
    }

    /** Two machines' numbers averaged into one anchor is the collision the machine field exists to refuse. */
    @Test
    fun inputsFromDifferentMachinesAreRefused() {
        val anchor = anchorCarrying(laneA to 1.0, laneB to 2.0)

        val run = refresh(
            anchor,
            observations("first.jsonl", laneA to 2.0, laneB to 4.0, machine = "AMD EPYC 7763"),
            observations("second.jsonl", laneA to 2.0, laneB to 4.0, machine = "Intel Xeon Platinum 8370C"),
        )

        assertNotEquals(0, run.status, "folding two machines into one anchor must be refused")
        assertTrue("machine" in run.stderr) { run.stderr }
        assertEquals(setOf("darwin-aarch64"), blocksIn(anchor), "a refused refresh must write nothing")
    }

    /**
     * **A hosted FLAG must stay foldable.** On shared hardware a breach is flagged rather than failed, and marking
     * it `breached` put it outside this script's fold — so one noise-driven flag dropped that lane from the
     * artifact and the next refresh was REFUSED for incomplete coverage, defeating the documented recovery path
     * with the very noise the flag exists to tolerate. Latent while the hosted ceilings were loose enough that
     * nothing breached; live from the 2026-08-13 refresh that tightened them.
     */
    @Test
    fun aFlaggedObservationIsFoldedSoAHostedFlagCannotBlockTheNextRefresh() {
        val anchor = anchorCarrying(laneA to 1.0, laneB to 2.0)

        val run = refresh(anchor, observations("hosted.jsonl", laneA to 2.0, laneB to 4.0, status = "flagged"))

        assertEquals(0, run.status, "a flagged observation is a measurement, not a rejection: ${run.stderr}")
        assertEquals(mapOf(laneA to 2.0, laneB to 4.0), observationsOf(anchor, "linux-amd64-ci"))
    }

    /** The other half of the same decision: a GATED rejection must never ratchet the bar it failed against. */
    @Test
    fun aBreachedObservationIsStillExcludedFromTheFold() {
        val anchor = anchorCarrying(laneA to 1.0, laneB to 2.0)

        val run = refresh(anchor, observations("gated.jsonl", laneA to 2.0, laneB to 4.0, status = "breached"))

        assertNotEquals(0, run.status,
            "folding a value the gate REJECTED would ratchet the anchor to a number a build already failed on")
        assertEquals(setOf("darwin-aarch64"), blocksIn(anchor), "a refused refresh must write nothing")
    }

    @Test
    fun showReportsTheDiffAndWritesNothing() {
        val anchor = anchorCarrying(laneA to 1.0, laneB to 2.0)
        val before = Files.readString(anchor)

        val run = run("show", anchor, listOf(observations("hosted.jsonl", laneA to 2.0, laneB to 4.0)))

        assertEquals(0, run.status, run.stderr)
        assertTrue("linux-amd64-ci" in run.stdout) { "show must report the block it would author: ${run.stdout}" }
        assertEquals(before, Files.readString(anchor), "show must leave the anchor byte-identical")
    }

    private fun anchorCarrying(vararg observations: Pair<String, Double>): Path =
        anchorWithBlocks("darwin-aarch64" to observations.toList())

    private fun anchorWithBlocks(vararg blocks: Pair<String, List<Pair<String, Double>>>): Path {
        val rendered = blocks.joinToString(",\n") { (environment, observations) ->
            val body = observations.joinToString(",\n") { (key, ms) -> """      "$key": $ms""" }
            """    "$environment": {
      "capturedAt": "2026-08-11",
      "commit": "abc1234",
      "processors": 8,
      "observations": {
$body
      }
    }"""
        }
        return Files.writeString(
            work.resolve("anchors.json"),
            "{\n  \"environments\": {\n$rendered\n  }\n}\n",
        )
    }

    /**
     * **With no arguments the refresh must pick the newest run by its STAMP, across every recorded environment.**
     *
     * This is the no-argument path — the one a maintainer actually types — and it was the only path with no case
     * here, which is exactly where the defect was. The default selection sorted whole PATHS, so the environment
     * directory sorted ahead of the timestamp and `linux-amd64-ci/…` beat every `darwin-aarch64/…` no matter how
     * old it was. Measured 2026-08-13 on the real tree: a gate run finished seconds earlier, the refresh ignored
     * it, and rewrote the *linux* block from a stale record — downgrading a committed 2-run median to a 1-run
     * block on different silicon.
     */
    @Test
    fun theDefaultInputIsTheNewestRunByStampAcrossEveryEnvironment() {
        val anchor = anchorCarrying(laneA to 1.0)
        val tree = Files.createDirectories(work.resolve("cwd/perf-results"))
        // The older run sits in the directory that sorts LAST, so a path-ordered selection prefers it.
        Files.createDirectories(tree.resolve("zz-late-alphabetically"))
        Files.writeString(
            tree.resolve("zz-late-alphabetically/2026-01-01T000000Z.jsonl"),
            recordText("zz-late-alphabetically", laneA to 9.99),
        )
        Files.createDirectories(tree.resolve("aa-early-alphabetically"))
        Files.writeString(
            tree.resolve("aa-early-alphabetically/2026-12-31T235959Z.jsonl"),
            recordText("aa-early-alphabetically", laneA to 1.23),
        )

        val run = run("refresh", anchor, emptyList(), resultsDirectory = tree)

        assertEquals(0, run.status, "the refresh failed: ${run.stderr}")
        assertTrue(
            blocksIn(anchor).contains("aa-early-alphabetically"),
            "the newest run by stamp (2026-12-31, in the directory that sorts FIRST) must be the default input; " +
                "the refresh chose ${blocksIn(anchor)} instead, so it is ordering by path rather than by stamp",
        )
        assertEquals(
            mapOf(laneA to 1.23), observationsOf(anchor, "aa-early-alphabetically"),
            "and it must anchor from that run's numbers",
        )
    }

    /** One run header plus its observations, as the record's JSON-Lines text. */
    private fun recordText(environment: String, vararg medians: Pair<String, Double>): String = buildString {
        appendLine(
            """{"kind":"run","environment":"$environment","os":"Linux","arch":"amd64","processors":4,""" +
                """"machine":"AMD EPYC 7763"}""",
        )
        medians.forEach { (key, ms) ->
            appendLine("""{"kind":"observation","environment":"$environment","key":"$key","medianMs":$ms}""")
        }
    }

    private fun observations(
        name: String,
        vararg medians: Pair<String, Double>,
        environment: String = "linux-amd64-ci",
        machine: String? = "AMD EPYC 7763",
        status: String? = null,
    ): Path {
        val names = machine?.let { ""","machine":"$it"""" } ?: ""
        val marked = status?.let { ""","status":"$it"""" } ?: ""
        val lines = buildString {
            appendLine(
                """{"kind":"run","environment":"$environment","os":"Linux","arch":"amd64","processors":4$names}""",
            )
            medians.forEach { (key, ms) ->
                appendLine(
                    """{"kind":"observation","environment":"$environment","key":"$key","medianMs":$ms$marked}""",
                )
            }
        }
        return Files.writeString(work.resolve(name), lines)
    }

    private fun refresh(anchor: Path, vararg inputs: Path, allowRemovals: Boolean = false) =
        run("refresh", anchor, inputs.toList(), allowRemovals)

    private fun run(
        mode: String,
        anchor: Path,
        inputs: List<Path>,
        allowRemovals: Boolean = false,
        resultsDirectory: Path? = null,
    ): Result {
        val process = ProcessBuilder(listOf(script.toString(), mode) + inputs.map { it.toString() })
            .apply {
                environment()["ANCHOR_FILE"] = anchor.toString()
                if (allowRemovals) environment()["ALLOW_REMOVALS"] = "1"
                resultsDirectory?.let { environment()["RESULTS_DIR"] = it.toString() }
            }
            .start()
        val stdout = process.inputStream.bufferedReader().readText()
        val stderr = process.errorStream.bufferedReader().readText()
        return Result(process.waitFor(), stdout, stderr)
    }

    private fun blocksIn(anchor: Path): Set<String> =
        PerfObservations.MAPPER.readTree(anchor.toFile()).path("environments")
            .properties().map { it.key }.toSet()

    private fun machineOf(anchor: Path, environment: String): String? =
        PerfObservations.MAPPER.readTree(anchor.toFile())
            .path("environments").path(environment).path("machine").takeIf { it.isTextual }?.asText()

    private fun observationsOf(anchor: Path, environment: String): Map<String, Double> =
        PerfObservations.MAPPER.readTree(anchor.toFile())
            .path("environments").path(environment).path("observations")
            .properties().associate { (key, value) -> key to value.asDouble() }

    private data class Result(val status: Int, val stdout: String, val stderr: String)
}
