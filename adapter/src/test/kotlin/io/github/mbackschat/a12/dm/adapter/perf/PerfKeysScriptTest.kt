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

    /**
     * **The positive control, and the reason the four refusals below are not enough on their own.** Every other
     * case here asserts that the script FAILS on bad input, which a script that failed on *everything* would also
     * satisfy — including the original stdin defect, whose symptom is an empty profile and therefore a loud
     * failure on every input. So one case has to prove the instrument actually reads a recording and reports what
     * is in it. `PERF_KEYS_JFR` stands in for the JDK's `jfr` tool (the same kind of seam as `perf-anchor.sh`'s
     * `ANCHOR_FILE`) because a guard cannot carry a binary flight recording.
     */
    @Test
    fun aReadableProfileIsReportedWithItsSampleCountAndItsCallers() {
        val fake = work.resolve("fake-jfr").apply {
            Files.writeString(this, FAKE_JFR_TOOL)
            toFile().setExecutable(true)
        }
        val recording = Files.writeString(work.resolve("any.jfr"), "stand-in; the fake tool ignores it")

        val run = run(recording.toString(), "ValueAdmissionCache.resultAt", env = mapOf("PERF_KEYS_JFR" to fake.toString()))

        assertEquals(0, run.status, "a readable profile is not a failure: ${run.output}")
        assertTrue("total ExecutionSamples = 3" in run.output) {
            "the report must state what it actually read, not an empty profile: ${run.output}"
        }
        assertTrue("java.util.HashMap.getNode" in run.output) { "the self-frame view must name the mechanic" }
        // The discriminating view: the mechanic is charged to the interpreter frame that built the key.
        assertTrue(Regex("""ValueAdmissionCache\.resultAt: in 2 samples""").containsMatchIn(run.output)) {
            "the by-caller view must attribute both samples to the requested frame: ${run.output}"
        }
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

    private fun run(vararg args: String, env: Map<String, String> = emptyMap()): Run {
        val builder = ProcessBuilder(listOf("bash", script.toString()) + args)
            .directory(script.parent.parent.toFile())
            .redirectErrorStream(true)
        builder.environment().putAll(env)
        val process = builder.start()
        val output = process.inputStream.bufferedReader().readText()
        return Run(process.waitFor(), output)
    }

    private companion object {
        /**
         * Stands in for `jfr print`, emitting three samples in the real tool's shape: two charged to
         * `ValueAdmissionCache.resultAt` through a `HashMap` mechanic, one to unrelated interpreter work. Enough
         * to distinguish "read the profile" from "reported nothing", which is the whole point of the control.
         */
        val FAKE_JFR_TOOL = """
            #!/usr/bin/env bash
            cat <<'SAMPLES'
            jdk.ExecutionSample {
              startTime = 13:17:21.489
              stackTrace = [
                java.util.HashMap.getNode(HashMap.java:1) line: 1
                io.github.mbackschat.a12.dm.interpreter.ValueAdmissionCache.resultAt(F.kt:2) line: 2
                io.github.mbackschat.a12.dm.interpreter.AutoChecks.computeFormalResultAt(A.kt:3) line: 3
              ]
            }
            jdk.ExecutionSample {
              startTime = 13:17:21.499
              stackTrace = [
                java.util.HashMap.getNode(HashMap.java:1) line: 1
                io.github.mbackschat.a12.dm.interpreter.ValueAdmissionCache.resultAt(F.kt:2) line: 2
                io.github.mbackschat.a12.dm.interpreter.AutoChecks.computeFormalResultAt(A.kt:3) line: 3
              ]
            }
            jdk.ExecutionSample {
              startTime = 13:17:21.509
              stackTrace = [
                io.github.mbackschat.a12.dm.interpreter.eval.ExprEvalKt.evalExpr(E.kt:9) line: 9
              ]
            }
            SAMPLES
        """.trimIndent()
    }
}
