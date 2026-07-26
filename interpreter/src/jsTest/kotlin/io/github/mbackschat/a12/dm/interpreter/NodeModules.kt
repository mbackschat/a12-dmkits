package io.github.mbackschat.a12.dm.interpreter

@JsModule("node:fs")
internal external object NodeFileSystem {
    fun existsSync(path: String): Boolean
    fun readdirSync(path: String): Array<String>
    fun statSync(path: String): NodeFileStats
    fun readFileSync(path: String, encoding: String): String
    fun mkdirSync(path: String, options: dynamic = definedExternally)
    fun writeFileSync(path: String, content: String, encoding: String)
    fun renameSync(source: String, target: String)
    fun unlinkSync(path: String)
}

internal external interface NodeFileStats {
    fun isDirectory(): Boolean
}

@JsModule("node:path")
internal external object NodePath {
    fun dirname(path: String): String
}

@JsModule("node:crypto")
internal external object NodeCrypto {
    fun createHash(algorithm: String): dynamic
}

@JsModule("node:v8")
internal external object NodeV8 {
    fun getHeapStatistics(): dynamic
}

@JsModule("node:perf_hooks")
internal external object NodePerformanceHooks {
    val performance: dynamic
}

@JsModule("node:inspector")
@JsName("Session")
internal external class NodeInspectorSession {
    fun connect()
    fun disconnect()
    fun post(method: String, callback: (dynamic, dynamic) -> Unit)
    fun post(method: String, parameters: dynamic, callback: (dynamic, dynamic) -> Unit)
}
