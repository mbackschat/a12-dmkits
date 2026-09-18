@file:Suppress("UnsafeCastFromDynamic")

package io.github.mbackschat.a12.dm.interpreter.footprint

import io.github.mbackschat.a12.dm.interpreter.NodeCrypto
import io.github.mbackschat.a12.dm.interpreter.NodeFileSystem
import io.github.mbackschat.a12.dm.interpreter.NodeInspector
import io.github.mbackschat.a12.dm.interpreter.NodePath
import io.github.mbackschat.a12.dm.interpreter.NodeV8
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.startCoroutine
import kotlin.js.Promise
import kotlin.js.jsTypeOf
import kotlin.test.Test

class FootprintNodeWorkerTest {

    @Test
    fun writeConfiguredFork(): Promise<Unit> {
        val platform = NodeFootprintPlatform()
        if (!FootprintWorkerRequest.enabled(platform)) return Promise.resolve(Unit)

        return promise {
            val request = FootprintWorkerRequest.fromEnvironment(platform)
            val fork = FootprintWorker.run(request.config, platform)
            platform.write(request.outputPath, FootprintProtocol.render(fork))
        }
    }

    private fun <T> promise(block: suspend () -> T): Promise<T> =
        Promise { resolve, reject ->
            block.startCoroutine(object : Continuation<T> {
                override val context = EmptyCoroutineContext
                override fun resumeWith(result: Result<T>) {
                    result.fold(resolve, reject)
                }
            })
        }
}

private class NodeFootprintPlatform : FootprintPlatform {
    private val process: dynamic = js("process")
    private val global: dynamic = js("globalThis")

    override val host = FootprintHost.NODE
    override val allocationMethod = "v8-inspector-sampling-${ALLOCATION_SAMPLING_INTERVAL}"

    override fun environment(name: String): String? =
        process.env[name] as? String

    override fun runtime(): FootprintRuntime {
        val heapStatistics = NodeV8.getHeapStatistics()
        return FootprintRuntime(
            name = "Node.js",
            version = process.version as String,
            os = process.platform as String,
            arch = process.arch as String,
            pid = (process.pid as Number).toLong(),
            maxHeapBytes = (heapStatistics.heap_size_limit as Number).toLong(),
            collector = "V8",
        )
    }

    override suspend fun requireExplicitGc(): FootprintGc {
        val explicit = jsTypeOf(global.gc) == "function"
        if (!explicit) {
            return FootprintGc(
                explicit = false,
                viable = false,
                method = "global-gc-weak-reference",
            )
        }
        val reference = NodeOwnerReference(WeakRef(Any()))
        return FootprintGc(
            explicit = true,
            viable = awaitCollection(listOf(reference)),
            method = "global-gc-weak-reference",
        )
    }

    override suspend fun postGcUsedBytes(): Long =
        List(POST_GC_SAMPLES) {
            forceCollection()
            (process.memoryUsage().heapUsed as Number).toLong()
        }.min()

    override suspend fun <T> measureAllocated(block: () -> T): FootprintAllocation<T> {
        val session = NodeInspector.Session()
        session.connect()
        try {
            val parameters: dynamic = js("({})")
            parameters.samplingInterval = ALLOCATION_SAMPLING_INTERVAL
            parameters.includeObjectsCollectedByMajorGC = true
            parameters.includeObjectsCollectedByMinorGC = true
            post(session, "HeapProfiler.startSampling", parameters)
            val value = block()
            val result = post(session, "HeapProfiler.stopSampling")
            val samples = result.profile.samples.unsafeCast<Array<dynamic>>()
            val bytes = samples.sumOf { sample -> (sample.size as Number).toLong() }
            return FootprintAllocation(value, bytes)
        } finally {
            session.disconnect()
        }
    }

    override fun ownerReference(owner: Any): FootprintOwnerReference =
        NodeOwnerReference(WeakRef(owner))

    override suspend fun ownersReleased(references: List<FootprintOwnerReference>): Boolean =
        awaitCollection(references.map { it as NodeOwnerReference })

    override fun reachabilityFence(vararg owners: Any) {
        global.__a12FootprintReachabilityFence = owners
        global.__a12FootprintReachabilityFence = null
    }

    override fun sha256(content: String): String =
        NodeCrypto.createHash("sha256").update(content, "utf8").digest("hex") as String

    override fun write(path: String, content: String) {
        val directory = NodePath.dirname(path)
        NodeFileSystem.mkdirSync(directory, js("({ recursive: true })"))
        val temporary = "$path.tmp-${process.pid}"
        try {
            NodeFileSystem.writeFileSync(temporary, content, "utf8")
            NodeFileSystem.renameSync(temporary, path)
        } finally {
            if (NodeFileSystem.existsSync(temporary)) NodeFileSystem.unlinkSync(temporary)
        }
    }

    private suspend fun post(
        session: NodeInspector.Session,
        method: String,
        parameters: dynamic = undefined,
    ): dynamic =
        kotlin.coroutines.suspendCoroutine { continuation ->
            val callback: (dynamic, dynamic) -> Unit = { error, result ->
                if (error == null) {
                    continuation.resume(result)
                } else {
                    continuation.resumeWithException(
                        IllegalStateException("$method failed: ${error.message}"),
                    )
                }
            }
            if (parameters === undefined) {
                session.post(method, callback)
            } else {
                session.post(method, parameters, callback)
            }
        }

    private suspend fun awaitCollection(references: List<NodeOwnerReference>): Boolean {
        repeat(GC_ATTEMPTS) {
            forceCollection()
            if (references.all(NodeOwnerReference::isReleased)) return true
        }
        return false
    }

    private suspend fun forceCollection() {
        require(jsTypeOf(global.gc) == "function") {
            "Node footprint measurement requires --expose-gc"
        }
        nextTurn()
        global.gc()
        nextTurn()
    }

    private suspend fun nextTurn(): Unit =
        kotlin.coroutines.suspendCoroutine { continuation ->
            val setImmediate: dynamic = js("setImmediate")
            setImmediate { continuation.resume(Unit) }
        }

    private class NodeOwnerReference(private val reference: WeakRef) : FootprintOwnerReference {
        override fun refersTo(owner: Any): Boolean = reference.deref() === owner
        override fun isReleased(): Boolean = reference.deref() == null
    }

    private companion object {
        const val ALLOCATION_SAMPLING_INTERVAL = 1_024
        const val POST_GC_SAMPLES = 3
        const val GC_ATTEMPTS = 80
    }
}

private external class WeakRef(target: Any) {
    fun deref(): Any?
}
