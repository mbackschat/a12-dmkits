package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.ComputationAlternative
import io.github.mbackschat.a12.dm.interpreter.model.ComputationDef
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import java.lang.ref.Reference
import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Deterministic NFR-F2 structural guards: logical cache counts must plateau for one live owner, and discarded
 * engines/documents must be collectible. Heap deltas remain a separate informative measurement.
 */
class CacheLifetimeGuardTest {

    @Test
    fun boundedOwnerScopedCachesPlateauAcrossMultipleCheckpointsWhileOwnersStayLive() {
        val observer = WeakCacheObserver()
        val (engine, document) = observedFixture(observer)
        val engineRef = WeakReference(engine)
        val documentRef = WeakReference(document)

        val checkpoints = buildList {
            repeat(CHECKPOINTS) {
                repeat(EVALUATIONS_PER_CHECKPOINT) {
                    engine.validate(document)
                    engine.compute(document)
                }
                add(observer.totalEntries())
                assertSame(engine, engineRef.get(), "the engine must stay strongly reachable while its caches are sampled")
                assertSame(document, documentRef.get(), "the document must stay strongly reachable while its caches are sampled")
                Reference.reachabilityFence(engine)
                Reference.reachabilityFence(document)
            }
        }

        CacheLifetimeGuard.assertPlateau(checkpoints)
        assertTrue(observer.totalEntries(CacheOwnerKind.DOCUMENT) > 0, "the document cache must actually be exercised")
        assertTrue(observer.totalEntries(CacheOwnerKind.PARSED_MODEL) > 0, "the prepared parse cache must actually be exercised")
        assertTrue(observer.peakEntries(CacheOwnerKind.OVERLAY_VIEWS) > 0,
            "the overlay-view cache must actually be exercised before its step ends")
        assertEquals(0, observer.totalEntries(CacheOwnerKind.OVERLAY_VIEWS),
            "each computation step must release its logical overlay-view entries")
    }

    @Test
    fun oneLogicalEntryLeakedPerEvaluationTripsTheSlopeGuard() {
        val plantedLeak = ArrayList<Any>()
        val samples = buildList {
            repeat(CHECKPOINTS) {
                repeat(EVALUATIONS_PER_CHECKPOINT) { plantedLeak += Any() }
                add(plantedLeak.size)
            }
        }

        assertFailsWith<AssertionError> { CacheLifetimeGuard.assertPlateau(samples) }
    }

    @Test
    fun discardedInterpreterAndDocumentOwnersReleaseAndLogicalCountsReturnToBaseline() {
        val observer = WeakCacheObserver()
        val queue = ReferenceQueue<Any>()
        val owners = createDiscardedOwners(observer, queue)
        assertTrue(observer.totalEntries() > 0, "the release guard must start from exercised caches")

        CacheLifetimeGuard.assertReleased(owners, queue)
        CacheLifetimeGuard.awaitCondition("logical cache observer did not return to baseline") {
            observer.totalEntries() == 0
        }
    }

    @Test
    fun aGlobalCacheRetainingDiscardedDocumentsTripsTheReleaseGuard() {
        val queue = ReferenceQueue<Any>()
        val retainedGlobally = ArrayList<Document>()
        val refs = List(8) {
            MODEL.newDocument().also(retainedGlobally::add).let { WeakReference(it as Any, queue) }
        }

        assertFailsWith<AssertionError> {
            CacheLifetimeGuard.assertReleased(refs, queue, gcCycles = 2)
        }
        retainedGlobally.clear()
        CacheLifetimeGuard.assertReleased(refs, queue)
    }

    private fun observedFixture(observer: WeakCacheObserver): Pair<DmInterpreter, Document> {
        val engine = DmInterpreter(MODEL)
        engine.cacheObserver = observer
        val document = MODEL.newDocument().group("/Ovl", 1)
        row(document, 1, "ka", "10")
        row(document, 2, "kb", "10")
        row(document, 3, "kc", "20")
        return engine to document
    }

    private fun createDiscardedOwners(
        observer: WeakCacheObserver,
        queue: ReferenceQueue<Any>,
    ): List<WeakReference<Any>> = buildList {
        repeat(OWNER_COUNT) {
            val (engine, document) = observedFixture(observer)
            engine.validate(document)
            engine.compute(document)
            add(WeakReference(engine, queue))
            add(WeakReference(document, queue))
            Reference.reachabilityFence(engine)
            Reference.reachabilityFence(document)
        }
    }

    private fun row(document: Document, index: Int, key: String, source: String) {
        document.group(ROW, 1, index)
            .field(KEY, key, 1, index, 1)
            .field(SOURCE, source, 1, index, 1)
    }

    private class WeakCacheObserver : CacheObserver {
        private val entries = WeakHashMap<Any, Pair<CacheOwnerKind, Int>>()
        private val peaks = CacheOwnerKind.entries.associateWithTo(HashMap()) { 0 }

        override fun cacheEntries(owner: Any, kind: CacheOwnerKind, entries: Int) {
            synchronized(this.entries) {
                peaks[kind] = maxOf(peaks.getValue(kind), entries)
                if (entries == 0) this.entries.remove(owner) else this.entries[owner] = kind to entries
            }
        }

        fun totalEntries(kind: CacheOwnerKind? = null): Int = synchronized(entries) {
            entries.values.filter { kind == null || it.first == kind }.sumOf { it.second }
        }

        fun peakEntries(kind: CacheOwnerKind): Int = synchronized(entries) { peaks.getValue(kind) }
    }

    private object CacheLifetimeGuard {
        fun assertPlateau(checkpoints: List<Int>) {
            assertTrue(checkpoints.size >= 3, "a plateau needs multiple post-warm checkpoints")
            val deltas = checkpoints.zipWithNext { before, after -> after - before }
            assertTrue(deltas.all { it == 0 },
                "logical cache entries did not plateau: checkpoints=$checkpoints deltas=$deltas")
        }

        fun assertReleased(
            refs: List<WeakReference<Any>>,
            queue: ReferenceQueue<Any>,
            gcCycles: Int = 80,
        ) {
            repeat(gcCycles) {
                forceCollection()
                if (refs.all { it.get() == null }) {
                    var dequeued = 0
                    while (queue.poll() != null) dequeued++
                    assertEquals(refs.size, dequeued, "every discarded owner must reach the reference queue")
                    return
                }
            }
            throw AssertionError("${refs.count { it.get() != null }} discarded owner(s) remain strongly reachable")
        }

        fun awaitCondition(message: String, condition: () -> Boolean) {
            repeat(80) {
                forceCollection()
                if (condition()) return
            }
            throw AssertionError(message)
        }

        private fun forceCollection() {
            System.gc()
            val pressure = Array(4) { ByteArray(128 * 1024) }
            Reference.reachabilityFence(pressure)
            Thread.sleep(10)
        }
    }

    private companion object {
        const val ROOT = "/Ovl"
        const val ROW = "$ROOT/Row"
        const val KEY = "$ROW/Key"
        const val SOURCE = "$ROW/Source"
        const val COMPUTED = "$ROW/Computed"
        const val CORRELATED = "$ROW/Correlated"
        const val INDEXED = "$ROW/Indexed"
        const val CHECKPOINTS = 5
        const val EVALUATIONS_PER_CHECKPOINT = 4
        const val OWNER_COUNT = 12

        val MODEL = ModelDef(
            fields = listOf(
                FieldDef(KEY, Kind.STRING),
                FieldDef(SOURCE, Kind.NUMBER, scale = 0),
                FieldDef(COMPUTED, Kind.NUMBER, scale = 0),
                FieldDef(CORRELATED, Kind.NUMBER, scale = 0),
                FieldDef(INDEXED, Kind.NUMBER, scale = 0),
            ),
            rules = listOf(
                RuleDef("$ROW/DuplicateSource", ROW,
                    "MoreThanOneFieldFilled($ROW*/Source Having [$SOURCE] == [\$$SOURCE])",
                    "DUP", "../Source"),
            ),
            computations = listOf(
                ComputationDef(COMPUTED, ROW, listOf(ComputationAlternative(null, "[$SOURCE] + 100"))),
                ComputationDef(CORRELATED, ROW, listOf(ComputationAlternative(
                    "MoreThanOneFieldFilled($ROW*/Computed Having [$COMPUTED] == [\$$COMPUTED])",
                    "[$COMPUTED]"))),
                ComputationDef(INDEXED, ROW, listOf(ComputationAlternative(null, "[$SOURCE For \"kb\"]"))),
            ),
            repeatableGroups = setOf(ROW),
            indexFields = setOf(KEY),
        )
    }
}
