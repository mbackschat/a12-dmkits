@file:Suppress("UnsafeCastFromDynamic")

package io.github.mbackschat.a12.dm.interpreter.performance

import io.github.mbackschat.a12.dm.interpreter.NodeCrypto
import io.github.mbackschat.a12.dm.interpreter.NodeFileSystem
import io.github.mbackschat.a12.dm.interpreter.NodePath
import io.github.mbackschat.a12.dm.interpreter.NodePerformanceHooks
import io.github.mbackschat.a12.dm.interpreter.NodeV8
import kotlin.test.Test

class PerformanceNodeWorkerTest {

    @Test
    fun writeConfiguredProcessSample() {
        val platform = NodePerformancePlatform()
        if (!PerformanceWorkerRequest.enabled(platform)) return
        val request = PerformanceWorkerRequest.fromEnvironment(platform)
        val fork = PerformanceWorker.run(request.config, platform)
        platform.write(request.outputPath, PerformanceProtocol.render(fork))
    }
}

private class NodePerformancePlatform : PerformancePlatform {
    private val process: dynamic = js("process")

    override val host = PerformanceHost.NODE

    override fun environment(name: String): String? = process.env[name] as? String

    override fun runtime(): PerformanceRuntime = PerformanceRuntime(
        name = "Node.js",
        version = process.version as String,
        os = process.platform as String,
        arch = process.arch as String,
        pid = (process.pid as Number).toLong(),
        maxHeapBytes = (NodeV8.getHeapStatistics().heap_size_limit as Number).toLong(),
    )

    override fun nanoTime(): Long =
        ((NodePerformanceHooks.performance.now() as Number).toDouble() * 1_000_000.0).toLong()

    /**
     * `process.cpuUsage()` — user plus system microseconds across EVERY thread of the process.
     *
     * Node exposes no per-thread CPU clock in stable form, so this is all the carrier can give, and it is not
     * the JVM's quantity: the libuv pool and V8's background GC and compiler threads are included, which is
     * why Node occupancy measures 1.0407–2.2500 on an idle machine (15 scale-samples, 2026-09-20) rather than
     * just under 1.0. It still falls
     * when the process is starved of cycles, which is the signal the floor reads, so the floor is keyed by host
     * rather than shared. Both halves are summed because a region that spent its time in the kernel still ran.
     */
    override fun cpuNanoTime(): Long {
        val usage = process.cpuUsage()
        val micros = (usage.user as Number).toDouble() + (usage.system as Number).toDouble()
        return (micros * 1_000.0).toLong()
    }

    /**
     * `null` — Node exposes no stable per-process GC accounting, and this carrier does not need it.
     *
     * The JVM records GC because its occupancy numerator EXCLUDES collection, which makes the ratio partly
     * post-treatment. Node's numerator is `process.cpuUsage()` across every thread, so V8's collector lands in
     * the numerator and the denominator alike and cannot depress the ratio the same way. Reporting `0.0` here
     * would be the absence-versus-zero error (IF285): it would read as "this region saw no collection", which is
     * not something this carrier measured.
     */
    override fun cumulativeGcMillis(): Double? = null

    override fun sha256(content: String): String =
        NodeCrypto.createHash("sha256").update(content, "utf8").digest("hex") as String

    override fun write(path: String, content: String) {
        NodeFileSystem.mkdirSync(NodePath.dirname(path), js("({ recursive: true })"))
        val temporary = "$path.tmp-${process.pid}"
        try {
            NodeFileSystem.writeFileSync(temporary, content, "utf8")
            NodeFileSystem.renameSync(temporary, path)
        } finally {
            if (NodeFileSystem.existsSync(temporary)) {
                NodeFileSystem.unlinkSync(temporary)
            }
        }
    }
}
