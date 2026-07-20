@file:Suppress("UnsafeCastFromDynamic")

package io.github.mbackschat.a12.dm.interpreter.performance

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
    private val performance: dynamic = js("require('node:perf_hooks').performance")
    private val fileSystem: dynamic = js("require('node:fs')")
    private val nodePath: dynamic = js("require('node:path')")
    private val crypto: dynamic = js("require('node:crypto')")
    private val v8: dynamic = js("require('node:v8')")

    override val host = PerformanceHost.NODE

    override fun environment(name: String): String? = process.env[name] as? String

    override fun runtime(): PerformanceRuntime = PerformanceRuntime(
        name = "Node.js",
        version = process.version as String,
        os = process.platform as String,
        arch = process.arch as String,
        pid = (process.pid as Number).toLong(),
        maxHeapBytes = (v8.getHeapStatistics().heap_size_limit as Number).toLong(),
    )

    override fun nanoTime(): Long =
        ((performance.now() as Number).toDouble() * 1_000_000.0).toLong()

    override fun sha256(content: String): String =
        crypto.createHash("sha256").update(content, "utf8").digest("hex") as String

    override fun write(path: String, content: String) {
        fileSystem.mkdirSync(nodePath.dirname(path), js("({ recursive: true })"))
        val temporary = "$path.tmp-${process.pid}"
        try {
            fileSystem.writeFileSync(temporary, content, "utf8")
            fileSystem.renameSync(temporary, path)
        } finally {
            if (fileSystem.existsSync(temporary) as Boolean) {
                fileSystem.unlinkSync(temporary)
            }
        }
    }
}
