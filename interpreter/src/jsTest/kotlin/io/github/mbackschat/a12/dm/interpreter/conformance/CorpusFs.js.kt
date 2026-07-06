package io.github.mbackschat.a12.dm.interpreter.conformance

/**
 * JS FS shim actual — reads the `corpus/cases` tree and the shared `corpus/models` files with Node's built-in `fs`
 * at test time (the Kotlin/JS `nodejs()` runner runs under Node, where `require` is available). The `corpus/cases`
 * directory comes from the `CORPUS_DIR` environment variable Gradle sets on `jsNodeTest` (the robust path — never a
 * relative guess from `process.cwd()`); a [modelRef] resolves against the corpus root (its parent). Pure Kotlin/JS
 * + Node built-ins: no `java.*`, no kernel (the purity lint's jsMain rule extends here by convention).
 */

private external interface NodeFs {
    fun existsSync(path: String): Boolean
    fun readdirSync(path: String): Array<String>
    fun statSync(path: String): NodeStats
    fun readFileSync(path: String, encoding: String): String
}

private external interface NodeStats {
    fun isDirectory(): Boolean
}

private val fs: NodeFs = js("require('fs')")

actual fun readCorpusFiles(): List<RawCorpusFile> {
    val dir = corpusCasesDir() ?: return emptyList()
    if (!fs.existsSync(dir)) return emptyList()
    val out = mutableListOf<RawCorpusFile>()
    walk(dir, out)
    return out.sortedBy { "${it.family}/${it.id}" }
}

actual fun readCorpusModel(modelRef: String): String {
    val root = corpusRoot()
        ?: throw IllegalStateException("cannot locate the corpus root to resolve modelRef '$modelRef' (CORPUS_DIR unset)")
    val file = "$root/$modelRef"
    if (!fs.existsSync(file)) throw IllegalArgumentException(
        "case modelRef '$modelRef' does not resolve — expected a corpus-root-relative model file " +
            "(e.g. \"models/<name>.json\") at $file",
    )
    return fs.readFileSync(file, "utf8")
}

private fun corpusCasesDir(): String? =
    js("(typeof process !== 'undefined' && process.env && process.env.CORPUS_DIR) ? process.env.CORPUS_DIR : null") as String?

/** The corpus root — `CORPUS_DIR` is the `corpus/cases` dir, so its parent is the root. */
private fun corpusRoot(): String? = corpusCasesDir()?.substringBeforeLast('/')

private fun walk(dir: String, out: MutableList<RawCorpusFile>) {
    for (name in fs.readdirSync(dir)) {
        val full = "$dir/$name"
        if (fs.statSync(full).isDirectory()) {
            walk(full, out)
        } else if (name.endsWith(".json")) {
            out += RawCorpusFile(dir.substringAfterLast('/'), name.removeSuffix(".json"), fs.readFileSync(full, "utf8"))
        }
    }
}
