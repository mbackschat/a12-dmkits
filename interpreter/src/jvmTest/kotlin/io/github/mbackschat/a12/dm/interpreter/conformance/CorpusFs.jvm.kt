package io.github.mbackschat.a12.dm.interpreter.conformance

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.name
import kotlin.io.path.readText

/**
 * JVM FS shim actual — reads the `corpus/cases` tree and the shared `corpus/models` files with `java.nio`. The
 * `corpus/cases` directory comes from `CORPUS_DIR` (Gradle-set); an IDE run with no env falls back to walking up to
 * the repo root (`settings.gradle`) and resolving `corpus`. A [modelRef] resolves against the corpus root (the
 * parent of `corpus/cases`).
 */
actual fun readCorpusFiles(): List<RawCorpusFile> {
    val dir = corpusRoot()?.resolve("cases") ?: return emptyList()
    if (!Files.exists(dir)) return emptyList()
    Files.walk(dir).use { stream ->
        return stream.filter { Files.isRegularFile(it) && it.name.endsWith(".json") }
            .map { RawCorpusFile(it.parent.name, it.name.removeSuffix(".json"), it.readText()) }
            .toList()
            .sortedBy { "${it.family}/${it.id}" }
    }
}

actual fun readCorpusModel(modelRef: String): String {
    val root = corpusRoot()
        ?: throw IllegalStateException("cannot locate the corpus root to resolve modelRef '$modelRef' (CORPUS_DIR unset and no settings.gradle found)")
    val file = root.resolve(modelRef)
    if (!Files.exists(file)) throw IllegalArgumentException(
        "case modelRef '$modelRef' does not resolve — expected a corpus-root-relative model file " +
            "(e.g. \"models/<name>.json\") at $file",
    )
    return file.readText()
}

/** The corpus root — `CORPUS_DIR` is the `corpus/cases` dir, so its parent is the root; else the settings.gradle walk-up. */
private fun corpusRoot(): Path? {
    System.getenv("CORPUS_DIR")?.let { return Paths.get(it).parent }
    // IDE fallback: walk up to the repo root (the dir carrying settings.gradle) and resolve corpus.
    var d: Path = Paths.get(System.getProperty("user.dir")).toAbsolutePath()
    while (d.parent != null && !Files.exists(d.resolve("settings.gradle"))) d = d.parent
    val corpus = d.resolve("corpus")
    return if (Files.exists(corpus)) corpus else null
}
