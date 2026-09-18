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

/**
 * `node:inspector`, reached through its module NAMESPACE like every binding above rather than as a class.
 *
 * **`@JsModule` on an external CLASS emits a DEFAULT import**, and a Node built-in's ESM default export is the
 * module object, not any one of its exports — so the emitted `new <module>()` threw
 * `TypeError: Session_… is not a constructor` and took the whole Node footprint arm down at its first
 * allocation measurement. `@JsName("Session")` does not redirect that import; it names the declaration, not
 * the binding, which is why the annotation pair read as correct. Declaring the module as an `external object`
 * emits `import * as`, and the nested class resolves as a member of it.
 *
 * The shape was right under the previous CommonJS output (`require('node:inspector').Session`) and broke when
 * the target moved to `useEsModules()`. Nothing caught it because this lane is local opt-in and the yarn-lock
 * gate refused before the worker ever ran.
 */
@JsModule("node:inspector")
internal external object NodeInspector {
    class Session {
        fun connect()
        fun disconnect()
        fun post(method: String, callback: (dynamic, dynamic) -> Unit)
        fun post(method: String, parameters: dynamic, callback: (dynamic, dynamic) -> Unit)
    }
}
