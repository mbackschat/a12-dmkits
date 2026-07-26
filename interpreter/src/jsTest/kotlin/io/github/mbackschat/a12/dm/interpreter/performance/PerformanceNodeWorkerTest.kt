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
