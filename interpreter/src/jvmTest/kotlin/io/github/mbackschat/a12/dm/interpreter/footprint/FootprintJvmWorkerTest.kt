package io.github.mbackschat.a12.dm.interpreter.footprint

import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory
import java.lang.ref.Reference
import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test

class FootprintJvmWorkerTest {

    @Test
    fun writeConfiguredFork() {
        val platform = JvmFootprintPlatform()
        if (!FootprintWorkerRequest.enabled(platform)) return

        val request = FootprintWorkerRequest.fromEnvironment(platform)
        val fork = runSuspend { FootprintWorker.run(request.config, platform) }
        platform.write(request.outputPath, FootprintProtocol.render(fork))
    }

    private fun <T> runSuspend(block: suspend () -> T): T {
        var outcome: Result<T>? = null
        block.startCoroutine(object : Continuation<T> {
            override val context = EmptyCoroutineContext
            override fun resumeWith(result: Result<T>) {
                outcome = result
            }
        })
        return checkNotNull(outcome) { "the JVM footprint platform must complete synchronously" }.getOrThrow()
    }
}

private class JvmFootprintPlatform : FootprintPlatform {
    private val allocationBean = (ManagementFactory.getThreadMXBean() as? ThreadMXBean)
        ?: throw IllegalStateException("the JVM does not expose com.sun.management.ThreadMXBean")

    init {
        require(allocationBean.isThreadAllocatedMemorySupported) {
            "the JVM does not support per-thread allocation measurement"
        }
        if (!allocationBean.isThreadAllocatedMemoryEnabled) {
            allocationBean.isThreadAllocatedMemoryEnabled = true
        }
    }

    override val host = FootprintHost.JVM
    override val allocationMethod = "thread-allocated-bytes"

    override fun environment(name: String): String? = System.getenv(name)

    override fun runtime(): FootprintRuntime = FootprintRuntime(
        name = System.getProperty("java.vm.name"),
        version = Runtime.version().toString(),
        os = System.getProperty("os.name"),
        arch = System.getProperty("os.arch"),
        pid = ProcessHandle.current().pid(),
        maxHeapBytes = Runtime.getRuntime().maxMemory(),
        collector = ManagementFactory.getGarbageCollectorMXBeans()
            .joinToString(separator = "+") { it.name },
    )

    override suspend fun requireExplicitGc(): FootprintGc {
        val queue = ReferenceQueue<Any>()
        val reference = discardedReference(queue)
        val viable = awaitCollection(listOf(reference))
        var dequeued = 0
        while (queue.poll() != null) dequeued++
        return FootprintGc(
            explicit = true,
            viable = viable && dequeued == 1,
            method = "system-gc-reference-queue",
        )
    }

    override suspend fun postGcUsedBytes(): Long =
        List(POST_GC_SAMPLES) {
            forceCollection()
            Runtime.getRuntime().let { runtime -> runtime.totalMemory() - runtime.freeMemory() }
        }.min()

    override suspend fun <T> measureAllocated(block: () -> T): FootprintAllocation<T> {
        val before = threadAllocatedBytes()
        val value = block()
        return FootprintAllocation(value, threadAllocatedBytes() - before)
    }

    override fun ownerReference(owner: Any): FootprintOwnerReference =
        JvmOwnerReference(WeakReference(owner))

    override suspend fun ownersReleased(references: List<FootprintOwnerReference>): Boolean =
        awaitCollection(references.map { (it as JvmOwnerReference).reference })

    override fun reachabilityFence(vararg owners: Any) {
        owners.forEach { owner -> Reference.reachabilityFence(owner) }
    }

    override fun sha256(content: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(content.toByteArray(StandardCharsets.UTF_8))
            .joinToString(separator = "") { "%02x".format(it) }

    override fun write(path: String, content: String) {
        val output = Path.of(path)
        Files.createDirectories(output.parent)
        val temporary = Files.createTempFile(output.parent, "${output.fileName}.", ".tmp")
        try {
            Files.writeString(temporary, content, StandardCharsets.UTF_8)
            try {
                Files.move(
                    temporary,
                    output,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun discardedReference(queue: ReferenceQueue<Any>): WeakReference<Any> =
        WeakReference(Any(), queue)

    private fun threadAllocatedBytes(): Long {
        val bytes = allocationBean.getThreadAllocatedBytes(Thread.currentThread().threadId())
        require(bytes >= 0) { "the JVM returned an invalid thread-allocation counter: $bytes" }
        return bytes
    }

    private fun awaitCollection(references: List<WeakReference<Any>>): Boolean {
        repeat(GC_ATTEMPTS) {
            forceCollection()
            if (references.all { it.get() == null }) return true
        }
        return false
    }

    private fun forceCollection() {
        System.gc()
        val pressure = Array(4) { ByteArray(128 * 1024) }
        Reference.reachabilityFence(pressure)
        Thread.sleep(10)
    }

    private data class JvmOwnerReference(val reference: WeakReference<Any>) : FootprintOwnerReference {
        override fun refersTo(owner: Any): Boolean = reference.get() === owner
        override fun isReleased(): Boolean = reference.get() == null
    }

    private companion object {
        const val POST_GC_SAMPLES = 3
        const val GC_ATTEMPTS = 80
    }
}
