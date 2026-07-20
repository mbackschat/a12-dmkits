package io.github.mbackschat.a12.dm.interpreter.footprint

import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FootprintWorkerTest {

    @Test
    fun workerMeasuresEveryScenarioAndScaleWithPositiveHostEvidence() {
        val result = runSuspend { FootprintWorker.run(CONFIG, FakePlatform()) }

        assertEquals(FootprintScenario.entries.size * CONFIG.scales.size, result.samples.size)
        assertTrue(result.samples.all { it.retainedBytes > 0 && it.allocatedBytes > 0 })
        assertTrue(result.samples.all { it.ownersReleased })
        FootprintProtocol.validateSingleFork(result, CONFIG.scales, FootprintScenario.entries)
    }

    @Test
    fun workerFailsBeforeEmissionWhenGcIsUnavailableOrOwnersLeakOrDeltasAreInvalid() {
        assertFailsWith<IllegalArgumentException> {
            runSuspend { FootprintWorker.run(CONFIG, FakePlatform(explicitGc = false)) }
        }
        assertFailsWith<IllegalArgumentException> {
            runSuspend { FootprintWorker.run(CONFIG, FakePlatform(ownersReleased = false)) }
        }
        assertFailsWith<IllegalArgumentException> {
            runSuspend { FootprintWorker.run(CONFIG, FakePlatform(memoryStep = 0)) }
        }
    }

    private fun <T> runSuspend(block: suspend () -> T): T {
        var outcome: Result<T>? = null
        block.startCoroutine(object : Continuation<T> {
            override val context = EmptyCoroutineContext
            override fun resumeWith(result: Result<T>) {
                outcome = result
            }
        })
        return checkNotNull(outcome) { "fake platform must complete synchronously" }.getOrThrow()
    }

    private class FakeReference(owner: Any) : FootprintOwnerReference {
        private var owner: Any? = owner

        override fun refersTo(owner: Any): Boolean = this.owner === owner
        override fun isReleased(): Boolean = owner == null
        fun release() {
            owner = null
        }
    }

    private class FakePlatform(
        private val explicitGc: Boolean = true,
        private val ownersReleased: Boolean = true,
        private val memoryStep: Long = 1_000,
    ) : FootprintPlatform {
        private var retained = 1_000_000L
        private val references = mutableListOf<FakeReference>()

        override val host = FootprintHost.JVM
        override val allocationMethod = "fake-monotonic-counter"

        override fun environment(name: String): String? = null

        override fun runtime(): FootprintRuntime = FootprintRuntime(
            name = "fake",
            version = "1",
            os = "test",
            arch = "test",
            pid = 42,
            maxHeapBytes = 256_000_000,
            collector = "fake",
        )

        override suspend fun requireExplicitGc(): FootprintGc =
            FootprintGc(explicitGc, explicitGc, "fake-explicit-gc")

        override suspend fun postGcUsedBytes(): Long {
            retained += memoryStep
            return retained
        }

        override suspend fun <T> measureAllocated(block: () -> T): FootprintAllocation<T> =
            FootprintAllocation(block(), memoryStep)

        override fun ownerReference(owner: Any): FootprintOwnerReference =
            FakeReference(owner).also(references::add)

        override suspend fun ownersReleased(references: List<FootprintOwnerReference>): Boolean {
            if (ownersReleased) this.references.forEach(FakeReference::release)
            return ownersReleased
        }

        override fun reachabilityFence(vararg owners: Any) = Unit

        override fun sha256(content: String): String = (content.length % 16).toString(16).repeat(64)

        override fun write(path: String, content: String) = Unit
    }

    private companion object {
        val CONFIG = FootprintWorkerConfig(
            commit = "0123456789abcdef",
            invocationId = "test-invocation",
            sampling = FootprintSampling(
                scales = listOf(4, 8, 16, 32),
                warmup = 1,
                repetitions = 2,
            ),
        )
    }
}
