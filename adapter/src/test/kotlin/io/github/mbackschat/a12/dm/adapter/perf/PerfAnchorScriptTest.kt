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
 * The sharp case is a **new** runner class. The blocked step in IG120 is authoring the hosted block from a nightly
 * artifact, and that artifact is uploaded `if: always()` — so the run most likely to be fed here is one where a
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
            "with several runs the anchor is their median, which is what makes the tolerance tightenable (IG120)")
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

    @Test
    fun showReportsTheDiffAndWritesNothing() {
        val anchor = anchorCarrying(laneA to 1.0, laneB to 2.0)
        val before = Files.readString(anchor)

        val run = run("show", anchor, listOf(observations("hosted.jsonl", laneA to 2.0, laneB to 4.0)))

        assertEquals(0, run.status, run.stderr)
        assertTrue("linux-amd64-ci" in run.stdout) { "show must report the block it would author: ${run.stdout}" }
        assertEquals(before, Files.readString(anchor), "show must leave the anchor byte-identical")
    }

    private fun anchorCarrying(vararg observations: Pair<String, Double>): Path {
        val block = observations.joinToString(",\n") { (key, ms) -> """      "$key": $ms""" }
        return Files.writeString(
            work.resolve("anchors.json"),
            """
            {
              "environments": {
                "darwin-aarch64": {
                  "capturedAt": "2026-08-11",
                  "commit": "abc1234",
                  "processors": 8,
                  "observations": {
            $block
                  }
                }
              }
            }
            """.trimIndent(),
        )
    }

    private fun observations(
        name: String,
        vararg medians: Pair<String, Double>,
        environment: String = "linux-amd64-ci",
    ): Path {
        val lines = buildString {
            appendLine("""{"kind":"run","environment":"$environment","os":"Linux","arch":"amd64","processors":4}""")
            medians.forEach { (key, ms) ->
                appendLine(
                    """{"kind":"observation","environment":"$environment","key":"$key","medianMs":$ms}""",
                )
            }
        }
        return Files.writeString(work.resolve(name), lines)
    }

    private fun refresh(anchor: Path, vararg inputs: Path, allowRemovals: Boolean = false) =
        run("refresh", anchor, inputs.toList(), allowRemovals)

    private fun run(mode: String, anchor: Path, inputs: List<Path>, allowRemovals: Boolean = false): Result {
        val process = ProcessBuilder(listOf(script.toString(), mode) + inputs.map { it.toString() })
            .apply {
                environment()["ANCHOR_FILE"] = anchor.toString()
                if (allowRemovals) environment()["ALLOW_REMOVALS"] = "1"
            }
            .start()
        val stdout = process.inputStream.bufferedReader().readText()
        val stderr = process.errorStream.bufferedReader().readText()
        return Result(process.waitFor(), stdout, stderr)
    }

    private fun blocksIn(anchor: Path): Set<String> =
        PerfObservations.MAPPER.readTree(anchor.toFile()).path("environments")
            .properties().map { it.key }.toSet()

    private fun observationsOf(anchor: Path, environment: String): Map<String, Double> =
        PerfObservations.MAPPER.readTree(anchor.toFile())
            .path("environments").path(environment).path("observations")
            .properties().associate { (key, value) -> key to value.asDouble() }

    private data class Result(val status: Int, val stdout: String, val stderr: String)
}
