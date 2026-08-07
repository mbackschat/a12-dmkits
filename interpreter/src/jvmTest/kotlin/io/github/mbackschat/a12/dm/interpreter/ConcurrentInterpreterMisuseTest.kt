package io.github.mbackschat.a12.dm.interpreter

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A `PreparedModel` is safe to share across request threads; an `Interpreter` is not. Sharing one across threads
 * used to be silently dangerous rather than loud: `evaluate` keeps a snapshot-scoped guard that the
 * custom-condition adapter reads, so one thread's callback could receive another thread's Document, and a peer's
 * `finally` could clear the guard underneath an in-flight call.
 *
 * This locks the corrective: concurrent use is DETECTED and refused with
 * [InterpreterIntegrationReason.CONCURRENT_USE]. It is a detector, not a mutex — the interpreter does not become
 * thread-safe, it becomes honest about not being thread-safe (IAG13).
 */
class ConcurrentInterpreterMisuseTest {

    private val json = PreparationWorkCounterTest.COMPUTED_MODEL_JSON

    @Test
    fun sharingOneInterpreterAcrossThreadsIsRefusedRatherThanSilentlyWrong() {
        val prepared = PreparedModel.loadExpandedJson(json)
        val document = prepared.documents.seedDocument()
        val shared = prepared.createInterpreter()

        val threads = 8
        val perThread = 60
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        val refusals = AtomicInteger()
        val completions = AtomicInteger()
        val unexpected = AtomicReference<Throwable?>(null)
        try {
            repeat(threads) {
                pool.execute {
                    start.await()
                    repeat(perThread) {
                        try {
                            shared.validateFull(document)
                            completions.incrementAndGet()
                        } catch (error: InterpreterIntegrationError) {
                            if (error.reason == InterpreterIntegrationReason.CONCURRENT_USE) {
                                refusals.incrementAndGet()
                            } else {
                                unexpected.compareAndSet(null, error)
                            }
                        } catch (error: Throwable) {
                            unexpected.compareAndSet(null, error)
                        }
                    }
                }
            }
            start.countDown()
            pool.shutdown()
            assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS), "the misuse run did not finish")
        } finally {
            pool.shutdownNow()
        }

        assertNull(unexpected.get(), "only a CONCURRENT_USE refusal is acceptable, got: ${unexpected.get()}")
        assertTrue(
            refusals.get() > 0,
            "concurrent use of one interpreter must be refused loudly at least once; " +
                "completions=${completions.get()} refusals=${refusals.get()}",
        )
        assertEquals(
            threads * perThread,
            completions.get() + refusals.get(),
            "every attempt must either complete or be refused, never be lost",
        )
    }

    /** The control: the supported shape — an interpreter per thread over one shared prepared model — never trips. */
    @Test
    fun anInterpreterPerThreadOverOneSharedPreparedModelIsNeverRefused() {
        val prepared = PreparedModel.loadExpandedJson(json)
        val document = prepared.documents.seedDocument()

        val threads = 8
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>(null)
        val completions = AtomicInteger()
        try {
            repeat(threads) {
                pool.execute {
                    try {
                        start.await()
                        repeat(60) {
                            prepared.createInterpreter().validateFull(document)
                            completions.incrementAndGet()
                        }
                    } catch (error: Throwable) {
                        failure.compareAndSet(null, error)
                    }
                }
            }
            start.countDown()
            pool.shutdown()
            assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS), "the supported run did not finish")
        } finally {
            pool.shutdownNow()
        }
        assertNull(failure.get(), "the supported per-request shape must never be refused: ${failure.get()}")
        assertEquals(threads * 60, completions.get())
    }
}
