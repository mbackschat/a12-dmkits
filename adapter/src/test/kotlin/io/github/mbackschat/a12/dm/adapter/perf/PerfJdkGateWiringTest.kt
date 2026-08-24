package io.github.mbackschat.a12.dm.adapter.perf

import java.io.File
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * The gate is WIRED, not merely written — every write path into `perf-results/` refuses on a foreign JVM, and a
 * refused run leaves the record tree exactly as it found it.
 *
 * <p><b>Why this class exists.</b> [PerfJdkGateTest] covers the pure decision, which is the easy half. The
 * wiring had already been wrong once: the refusal sat on `record()` alone, so `recordWarm`/`recordCost`/
 * `recordMargin` still wrote and a refused GraalVM run produced a file with 102 warm records and 0 observations.
 * It was wrong a second way underneath that: `pruneToRetentionBound` runs BEFORE the write, so the same run
 * deleted a committed record to make room for a file it then refused to fill. Both were fixed and verified by a
 * one-off manual GraalVM run described in a commit message — which a cold review on 2026-08-24 correctly refused
 * to accept as a control, because deleting either `requireSupported()` call failed no test at all. On OpenJDK 21
 * the call is provably a no-op, so the only way to hold it is to substitute the judged identity.
 */
class PerfJdkGateWiringTest {

    private val graalvm = JdkIdentity.of("Java HotSpot(TM) 64-Bit Server VM", "21.0.12+7-LTS-jvmci-23.1-b96", "21")

    /**
     * EVERY case is sandboxed, not just the one that inspects the directory. The first version scoped
     * `perf.results.dir` to a single test, and the others reached `PerfObservations.file` — whose lazy
     * initializer prunes and creates against the REAL tree. It deleted two committed runs and left a 0-byte
     * file behind, which `PerfReportScriptTest` then failed on. A test that can damage the record it guards is
     * the same defect class as the code it is testing.
     */
    private var previousDir: String? = null

    @BeforeEach fun sandbox(@TempDir sandbox: File) {
        previousDir = System.getProperty("perf.results.dir")
        System.setProperty("perf.results.dir", sandbox.absolutePath)
    }

    @AfterEach fun restore() {
        JdkIdentity.judged = null
        previousDir?.let { System.setProperty("perf.results.dir", it) } ?: System.clearProperty("perf.results.dir")
    }

    private fun key() = ObservationKey("lane", "scenario@1", 100, "interpreter", "cpu")

    /**
     * What this does and does NOT isolate, stated because a mutation run settled it. Removing the gate from
     * `newRunFile` kills the third case below (mutant DIES); removing it from `File.appendLine` kills nothing
     * (mutant SURVIVES) — every write reaches the record tree through the `file` lazy, which refuses first, and
     * the JVM identity cannot change mid-process. So this asserts the CONTRACT ("no record kind writes on a
     * foreign JVM") and not which of the two gates enforces it; the load-bearing one is `newRunFile`.
     */
    @Test
    fun `every record kind refuses on a foreign JVM, not just the medians`() {
        JdkIdentity.judged = graalvm
        // One case per write path. The first version of this gate would have passed the first and failed the
        // other three, which is exactly the defect that shipped.
        assertThrows(IllegalStateException::class.java) { PerfObservations.record(key(), 1.0, "accepted") }
        assertThrows(IllegalStateException::class.java) { PerfObservations.recordWarm("l", "s", 1, "interpreter", 1, "cap") }
        assertThrows(IllegalStateException::class.java) { PerfObservations.recordCost("l", "s", 1.0) }
        assertThrows(IllegalStateException::class.java) { PerfObservations.recordMargin(key(), 1.0, null, "accepted") }
    }

    @Test
    fun `the refusal names the JVM it found so the reader can act on it`() {
        JdkIdentity.judged = graalvm
        val thrown = assertThrows(IllegalStateException::class.java) { PerfObservations.recordCost("l", "s", 1.0) }
        val message = thrown.message.orEmpty()
        assertTrue(message.contains("GraalVM 21"), "must name what it found: $message")
        assertTrue(message.contains("gradle-environment.sh"), "must point at the pin, not the anchors: $message")
    }

    @Test
    fun `a refused run neither writes a record nor prunes a committed one`(@TempDir dir: File) {
        // The ordering half: pruneToRetentionBound DELETES to make room, and it runs before the first write.
        val env = File(dir, PerfEnvironment.key).apply { mkdirs() }
        val existing = File(env, "2020-01-01T000000Z.jsonl").apply { writeText("{\"kind\":\"run\"}\n") }
        val before = env.list()!!.toSortedSet()

        System.setProperty("perf.results.dir", dir.absolutePath)
        JdkIdentity.judged = graalvm
        assertThrows(IllegalStateException::class.java) { PerfObservations.newRunFile() }

        assertEquals(before, env.list()!!.toSortedSet(), "a refused run must add no file and remove none")
        assertTrue(existing.exists(), "a refused run must not prune a committed record to make room for itself")
    }

    @Test
    fun `the happy path is untouched — the gate admits this suite's own JVM`() {
        // The positive control. Without it, a gate that refused EVERYTHING would satisfy the three cases above.
        JdkIdentity.judged = null
        assertEquals(null, JdkIdentity.refusalFor(JdkIdentity.current),
            "this suite runs on ${JdkIdentity.current.text}, which the gate must admit")
    }
}
