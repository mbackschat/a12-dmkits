package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.LegalCharset
import io.github.mbackschat.a12.dm.interpreter.model.MessageLabelProvider
import io.github.mbackschat.a12.dm.interpreter.model.PredefinedTypeRegistry
import io.github.mbackschat.a12.dm.interpreter.performance.PerformanceModel
import io.github.mbackschat.a12.dm.interpreter.performance.PerformanceModelFixtures
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The P-SERVICE concurrency contract, on the host where it matters. A JVM service serves requests on many threads
 * against a bounded cache of prepared models, so two properties must hold and only one of them is about reuse:
 *
 * 1. **One `PreparedModel` is safe to share across request threads.** Its model-static preparation is memoized in
 *    SYNCHRONIZED `lazy` state, so concurrent first-touch races build each plan once and every thread observes the
 *    same result.
 * 2. **Each request gets its OWN `Interpreter`.** That is a SAFETY requirement, not a cost preference:
 *    `Interpreter` carries an unsynchronized snapshot-scoped guard that the custom-condition adapter reads, so
 *    sharing one instance across threads can hand a callback another thread's Document. This class pins the safe
 *    shape; the unsafe shape is a documented contract, not a supported one.
 *
 * The sequential no-leftover-state property is `SharedPreparationIsolationTest`; this class adds the thread axis.
 */
class SharedPreparationConcurrencyTest {

    private val json = PerformanceModelFixtures.jsonFor(PerformanceModel.MANY_RULES_192)

    /** Rules alone cover 2 of the 7 preparation classes; the computed model reaches the computation ones. */
    private val computedJson = PreparationWorkCounterTest.COMPUTED_MODEL_JSON

    @Test
    fun concurrentRequestsShareOnePreparedModelAndAgreeWithTheSequentialBaseline() {
        val prepared = PreparedModel.loadExpandedJson(json)
        val document = prepared.documents.seedDocument()

        // The sequential baseline: what one request answers when nothing is racing.
        val baseline = prepared.createInterpreter().let { describe(it.validateFull(document)) }

        val threads = 8
        val perThread = 25
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        val answers = ConcurrentLinkedQueue<String>()
        val failure = AtomicReference<Throwable?>(null)
        try {
            repeat(threads) {
                pool.execute {
                    try {
                        start.await()
                        repeat(perThread) {
                            // Each request builds its OWN interpreter over the SHARED prepared model.
                            val interpreter = prepared.createInterpreter()
                            answers += describe(interpreter.validateFull(document))
                        }
                    } catch (error: Throwable) {
                        failure.compareAndSet(null, error)
                    }
                }
            }
            start.countDown()
            pool.shutdown()
            assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS), "concurrent requests did not finish in time")
        } finally {
            pool.shutdownNow()
        }

        assertNull(failure.get(), "a concurrent request failed: ${failure.get()}")
        assertEquals(threads * perThread, answers.size, "every concurrent request must produce an answer")
        val distinct = answers.toSet()
        assertEquals(
            setOf(baseline),
            distinct,
            "concurrent requests over one shared prepared model must all agree with the sequential baseline",
        )
    }

    /**
     * The race that sharing introduces: many threads touching a model-static plan for the first time at once. Each
     * plan must be built exactly ONCE, so the preparation work classes report the same counts a single-threaded
     * warm-up reports. A double build would be visible as inflated counts even when the results still agree.
     */
    @Test
    fun concurrentFirstTouchBuildsEachModelStaticPlanExactlyOnce() {
        val sequentialShape = observedShape(threads = 1, perThread = 1)
        val concurrentShape = observedShape(threads = 8, perThread = 3)
        assertTrue(sequentialShape.isNotEmpty(), "the warm-up must have performed model-static work to compare")
        assertEquals(
            sequentialShape,
            concurrentShape,
            "a concurrent first touch must not build any model-static plan more than once",
        )
    }

    /**
     * Races a model carrying rules AND computations, so the raced set is not only the two rule classes: the
     * computation AST/plan, the implicit-rule AST and the whole-model dependency order are memos whose
     * initializers nest, which is where a first-touch race would actually bite.
     */
    private fun observedShape(threads: Int, perThread: Int): Map<PreparationWork, Int> {
        val model = ModelLoader.load(computedJson)
        val plans = ModelStaticPlans.of(model)
        val events = ConcurrentLinkedQueue<PreparationEvent>()
        plans.preparationObserver = PreparationObserver(events::add)
        val document = model.newDocument()

        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>(null)
        try {
            repeat(threads) {
                pool.execute {
                    try {
                        start.await()
                        repeat(perThread) {
                            val engine = DmInterpreter.sharing(
                                model = model,
                                clock = null,
                                predefinedTypes = PredefinedTypeRegistry.DEFAULT,
                                customConditions = emptyMap(),
                                customFieldTypes = emptyMap(),
                                legalCharset = LegalCharset.BMP,
                                messageLabelProvider = MessageLabelProvider.MODEL_LABEL_OR_NAME,
                                plans = plans,
                            )
                            engine.supportAudit()
                            engine.validate(document)
                            engine.compute(document)
                        }
                    } catch (error: Throwable) {
                        failure.compareAndSet(null, error)
                    }
                }
            }
            start.countDown()
            pool.shutdown()
            assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS), "concurrent preparation did not finish in time")
        } finally {
            pool.shutdownNow()
        }
        assertNull(failure.get(), "a concurrent preparation failed: ${failure.get()}")
        return events.groupingBy { it.work }.eachCount()
    }

    private fun describe(report: ValidationReport): String =
        report.findings.map { "${it.code}|${it.severity}|${it.type}" }.sorted().joinToString(";")
}
