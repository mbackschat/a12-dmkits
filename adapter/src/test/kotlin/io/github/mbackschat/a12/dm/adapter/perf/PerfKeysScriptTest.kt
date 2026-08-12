package io.github.mbackschat.a12.dm.adapter.perf

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * The refusals of `scripts/perf-keys.sh`, **executed** rather than read as source text.
 *
 * [IF285] cites this script as the reproducible instrument behind the list-in-key sweep, so its numbers are only
 * worth anything if it cannot report an EMPTY profile as a result. That is not a hypothetical failure: the
 * analyzer reads the recording from stdin, and the embedded-heredoc form every sibling perf script uses
 * (`python3 - <<'PY'`) takes stdin over — the first version of this script therefore parsed a correct script
 * against no input and printed a clean report of nothing. It now fails loudly, and this holds it there.
 *
 * The driving modes are deliberately NOT exercised: each runs a 20 s profiling loop, which is exactly the kind of
 * cost that does not belong in a guard lane. What is checked is everything reachable without measuring.
 */
class PerfKeysScriptTest {

    private val script = Path.of(
        checkNotNull(System.getProperty("perf.keys.script")) {
            "run this through :adapter:perfScriptGuardTest, which passes the script's path and declares it as a " +
                "task input; the broad :adapter:test excludes this class for that reason"
        },
    )

    @TempDir
    lateinit var work: Path

    /**
     * The recording is deliberately NON-EMPTY and invalid, so the size check upstream passes it through and the
     * ANALYZER is the thing under test — it receives no samples and must say so. A zero-byte file would be
     * refused one step earlier and would leave this path, the one that actually broke, unexercised.
     */
    @Test
    fun aRecordingTheAnalyzerCannotReadIsAFailureAndNotAnEmptyReport() {
        val garbage = Files.write(work.resolve("garbage.jfr"), "not a flight recording".toByteArray())

        val run = run(garbage.toString())

        assertNotEquals(0, run.status,
            "a sample-free profile must fail: reported as success it would read as 'this code does no key " +
                "hashing', which is the one conclusion the instrument must never invent")
        assertTrue("no ExecutionSamples" in run.output) {
            "the analyzer must name the empty result rather than print a report of nothing: ${run.output}"
        }
    }

    @Test
    fun anEmptyOrMissingRecordingIsRefusedBeforeTheAnalyzer() {
        val absent = run(work.resolve("absent.jfr").toString())
        assertNotEquals(0, absent.status, "a path that does not exist cannot be a profile")
        assertTrue("no recording" in absent.output) { "the refusal must name it: ${absent.output}" }

        val empty = run(Files.createFile(work.resolve("empty.jfr")).toString())
        assertNotEquals(0, empty.status, "a zero-byte recording is the shape a killed profiling run leaves")
        assertTrue("no recording" in empty.output) { "the refusal must name it: ${empty.output}" }
    }

    @Test
    fun anUnknownModeIsRefusedWithItsUsage() {
        val run = run("bench")

        assertEquals(2, run.status, "an unknown mode is a usage error, not a measurement failure")
        assertTrue("unknown mode" in run.output) { "the refusal must name the mode: ${run.output}" }
    }

    @Test
    fun noModeAtAllPrintsTheUsage() {
        val run = run()

        assertEquals(2, run.status)
        assertTrue("usage:" in run.output) { "a bare invocation must print its usage: ${run.output}" }
    }

    private class Run(val status: Int, val output: String)

    private fun run(vararg args: String): Run {
        val process = ProcessBuilder(listOf("bash", script.toString()) + args)
            .directory(script.parent.parent.toFile())
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        return Run(process.waitFor(), output)
    }
}
