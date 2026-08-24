package io.github.mbackschat.a12.dm.adapter.perf

import java.io.File
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
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
     * EVERY case sets `perf.results.dir`, but be precise about what that buys (corrected 2026-08-24, cold
     * review). `resultsDirectory()` is read inside the `file` lazy, so the property only takes effect for a case
     * that reaches an UNBOUND lazy — cases 3 and 4, which call `newRunFile()` directly, are genuinely sandboxed.
     * Cases 1 and 2 are protected by the gate itself: they refuse before any write, which is the contract under
     * test. The sandbox is still set for all of them because the first version scoped it to a single test and
     * the others pruned the REAL tree — two committed runs deleted and a 0-byte file left behind, which
     * `PerfReportScriptTest` then failed on. A test that can damage the record it guards is the same defect
     * class as the code it is testing.
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
     * What this does and does NOT isolate, stated because a mutation run settled it — and the earlier version of
     * this note over-generalized that run (corrected 2026-08-24, cold review). All four verdicts below are
     * measured with `scripts/mutant.sh` against this class. Removing the gate from `newRunFile` DIES (caught by
     * the neither-writes-nor-prunes case); replacing `requireSupported`'s body with an unconditional throw DIES
     * (the happy-path case, which is why that case goes through `newRunFile` and not `refusalFor`); removing the
     * gate from `record` DIES (the quality-baseline case). Removing it from `File.appendLine` kills nothing
     * IN THIS LANE, because `:adapter:test` excludes the `perf` tag, so the lazy is unbound and every call
     * re-enters `newRunFile`. That is a property of the lane, not of the code: under `-PallTests`/`-PrunPerf` a
     * perf class binds the lazy first and the `appendLine` gate becomes the only one left. So this asserts the
     * CONTRACT ("no record kind writes on a foreign JVM") and deliberately not which gate enforces it.
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
    fun `a refused record leaves the shared quality baseline where it found it`() {
        // The control for the gate at the TOP of `record()`. It cannot be held through the record file: with an
        // unbound `file` lazy the call re-enters `newRunFile`, which refuses too, so deleting the entry gate
        // still throws and writes nothing (mutant SURVIVED, measured 2026-08-24). What the entry gate uniquely
        // protects is in-memory — `record` snapshots and advances `lastQuality` before it appends, so a refusal
        // that fired downstream would leave the NEXT genuine observation measuring its window against this
        // test's snapshot. Comparing before/after rather than asserting null, so the lane's history is irrelevant.
        val before = PerfObservations.lastQuality
        JdkIdentity.judged = graalvm

        assertThrows(IllegalStateException::class.java) { PerfObservations.record(key(), 1.0, "accepted") }

        assertSame(before, PerfObservations.lastQuality,
            "a refused record must not advance the window baseline the next real observation is measured against")
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
    fun `the happy path is untouched — the gate admits this suite's own JVM`(@TempDir dir: File) {
        // The positive control, and it must run through the WIRED seam. Asserting `refusalFor(current) == null`
        // was not one: it calls the pure decision directly, so an unconditional `throw` planted in
        // `requireSupported()` — the very seam that was wired wrong twice — passed all four cases (cold review
        // 2026-08-24, mutant SURVIVED). Going through `newRunFile` kills that mutant, and it is the right entry
        // point: it exercises the load-bearing gate without binding the `file` lazy, which would pin the rest of
        // this fork to a @TempDir that JUnit is about to delete.
        System.setProperty("perf.results.dir", dir.absolutePath)
        JdkIdentity.judged = null

        val created = assertDoesNotThrow<File>(
            { PerfObservations.newRunFile() },
            "this suite runs on ${JdkIdentity.current.text}, which the gate must admit",
        )

        assertTrue(created.isFile, "an admitted run must create its record file")
        assertTrue(created.readText().contains("\"kind\":\"run\""), "an admitted run writes its header: ${created.readText()}")
    }
}
