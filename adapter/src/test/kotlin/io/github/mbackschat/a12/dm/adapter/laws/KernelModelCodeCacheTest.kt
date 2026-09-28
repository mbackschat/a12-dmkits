package io.github.mbackschat.a12.dm.adapter.laws

import com.mgmtp.a12.kernel.md.rt.api.IModelCode
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * The cross-run cache of the kernel's GENERATED code — that it changes no answer, and that it is safe to be wrong.
 *
 * **What is being protected.** [KernelModelCodeCache] exists to skip code generation, never evaluation, so the
 * property that matters is that a cached run and an uncached run produce the *same kernel answer*. Everything
 * else here defends the ways a cache can quietly stop being one: a key that does not separate models, an entry
 * that survives a kernel or JDK change it should not, and a corrupt file that turns a slow run into a red one.
 *
 * **Why the oracle is a comparison and not a golden.** Asserting the cached run against a recorded message set
 * would test the cache against the same class of artifact the cache is forbidden to become. Both arms here run
 * the real kernel; only the generation path differs.
 *
 * Own domain: an e-commerce order with a threshold rule, and a second model that differs by one bound.
 */
class KernelModelCodeCacheTest {

    /** The headline: a cached second run answers exactly what the uncached first one did. */
    @Test
    fun aCachedRunProducesTheKernelsOwnAnswerUnchanged() {
        val uncached = withCache(enabled = false) { messagesFor(model(threshold = 5)) }
        val populating = withCache(enabled = true) { messagesFor(model(threshold = 5)) }
        val reading = withCache(enabled = true) { messagesFor(model(threshold = 5)) }

        assertEquals(uncached, populating, "populating the cache may not change what the kernel reports")
        assertEquals(uncached, reading, "…and neither may reading it back")
        assertTrue(uncached.isNotEmpty(), "…over a model that actually fires, or all three agree on nothing")
    }

    /**
     * The cache really was used — without this, every other assertion here is satisfied by a cache that never
     * stores anything, which is exactly what a broken one looks like.
     */
    @Test
    fun theSecondRunReadsWhatTheFirstOneWrote() {
        withCache(enabled = true) { messagesFor(model(threshold = 5)) }

        val entries = Files.walk(dir).filter { it.toString().endsWith(".ser.gz") }.toList()
        assertTrue(entries.isNotEmpty(), "the first run must leave an entry behind, or nothing is being cached")
        assertTrue(entries.none { it.toString().endsWith(".tmp") },
            "…and no staging file survives, or a killed fork would leave a half-entry that reads as a hit")
    }

    /**
     * Two models must not share an entry. Asserted on the entry LAYOUT rather than on behaviour, because a key
     * collision shows up as a wrong answer only for models whose generated code differs in a way this document
     * happens to expose — the failure would be real and the test would be silent about it.
     */
    @Test
    fun twoModelsAreFiledSeparately() {
        withCache(enabled = true) { messagesFor(model(threshold = 5)) }
        assertEquals(1, fingerprintDirectories(), "the first model gets one fingerprint directory")

        withCache(enabled = true) { messagesFor(model(threshold = 9)) }
        assertEquals(2, fingerprintDirectories(),
            "a model differing by one bound gets its OWN — a shared entry would serve one model's generated "
                + "code to the other, which is a wrong answer no document in this test need expose")
    }

    /**
     * A corrupt entry is a miss, not a failure — the property that makes the cache safe to leave enabled.
     *
     * Truncation stands in for the whole class: a fork killed mid-write, a partial disk, a class that stopped
     * deserializing after a dependency bump. All of them reach the same read path.
     */
    @Test
    fun acorruptEntryIsTreatedAsAMissRatherThanAFailure() {
        val expected = withCache(enabled = true) { messagesFor(model(threshold = 5)) }
        val entry = Files.walk(dir).filter { it.toString().endsWith(".ser.gz") }.findFirst().orElseThrow()
        Files.write(entry, byteArrayOf(0, 1, 2, 3))

        val afterCorruption = withCache(enabled = true) { messagesFor(model(threshold = 5)) }

        assertEquals(expected, afterCorruption, "the kernel regenerates and answers exactly as before")
        assertFalse(Files.exists(entry) && Files.size(entry) == 4L,
            "…and the unreadable entry is dropped rather than re-read on every later run")
    }

    /** Disabled is not merely uncached: it is the engine shape every lane had before this cache existed. */
    @Test
    fun theDisabledPathWritesNothingAtAll() {
        withCache(enabled = false) { messagesFor(model(threshold = 5)) }

        assertTrue(Files.walk(dir).noneMatch { it.toString().endsWith(".ser.gz") },
            "a lane that did not opt in must leave no entry, or a perf lane could still read one")
    }

    @Test
    fun residentCodeIsBoundedAndEvictedCodeStillLoadsFromDisk() {
        withCache(enabled = true) {
            val cache = KernelModelCodeCache.forModel(model(threshold = 5))
            val first = StubModelCode("hash-0")
            cache.addModelCode("code-0", first)
            for (index in 1..64) cache.addModelCode("code-$index", StubModelCode("hash-$index"))

            assertEquals(64, KernelModelCodeCache.residentSize())
            val reloaded = cache.getModelCode("code-0")
            assertNotSame(first, reloaded, "a cold entry must come from disk after eviction")
            assertEquals("hash-0", reloaded.modelHash)
        }
    }

    private data class StubModelCode(private val hash: String) : IModelCode {
        override fun getModelHash(): String = hash
    }

    // ---- fixtures --------------------------------------------------------------------------------

    private fun fingerprintDirectories(): Int =
        Files.list(dir).filter { Files.isDirectory(it) }.toList().size

    private fun messagesFor(modelJson: String): List<String> {
        val engine = RuntimeLaws.kernelEngine(modelJson)
        val doc = """{"Order":{"Reference":"R1","Total":10.00}}"""
        return engine.validationMessages(engine.instance(doc), null, Locale.US, null)
            .map { it.toString() }.sorted()
    }

    /** An order whose total is checked against one bound — the bound is what makes two models differ. */
    private fun model(threshold: Long): String {
        val base = BoundModel.ofJson(
            DocumentModelDraft.named("code-cache-$threshold").locale("en_US")
                .rootGroup("Order") { o ->
                    o.stringField("Reference") { it.maxLength(20) }
                        .numberField("Total") { it.fractionDigits(2, 2) }
                }.build().toJson())
        val rule = base.rule(base.group("/Order")).name("OverThreshold")
            .errorField(base.field("/Order/Total"))
            .`when`(base.num("/Order/Total").gt(threshold))
            .code("OVER").severity(Severity.ERROR)
            .message(Dsl.en("The order total is over $threshold.")).build()
        return base.addAll(listOf(rule), emptyList()).toJson()
    }

    /**
     * Runs a block with the cache switched on or off and pointed at this test's own directory.
     *
     * The properties are restored afterwards because the whole fork shares them, and [RuntimeLaws]' engine cache
     * is bypassed by giving each arm a distinct model text — otherwise a second arm would reuse the first arm's
     * live engine and never reach the code cache at all.
     */
    private fun <T> withCache(enabled: Boolean, body: () -> T): T {
        val previousEnabled = System.getProperty(ENABLED)
        val previousDir = System.getProperty(DIRECTORY)
        System.setProperty(ENABLED, enabled.toString())
        System.setProperty(DIRECTORY, dir.toString())
        // The JVM-level resident map would otherwise serve every arm after the first, so no arm but the first
        // would reach the disk — and the unreadable-entry test would pass without ever opening the file.
        KernelModelCodeCache.clearResident()
        try {
            return body()
        } finally {
            restore(ENABLED, previousEnabled)
            restore(DIRECTORY, previousDir)
        }
    }

    private fun restore(key: String, value: String?) {
        if (value == null) System.clearProperty(key) else System.setProperty(key, value)
    }

    /**
     * Per-TEST, not per-class: these methods assert on what is present in the directory, so a static one would
     * let an earlier method's entries satisfy or break a later one depending on execution order.
     */
    @TempDir
    lateinit var dir: Path

    companion object {
        private const val ENABLED = "rk.kernelCodeCache"
        private const val DIRECTORY = "rk.kernelCodeCacheDir"
    }
}
