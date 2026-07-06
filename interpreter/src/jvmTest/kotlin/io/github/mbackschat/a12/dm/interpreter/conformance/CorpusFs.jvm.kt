package io.github.mbackschat.a12.dm.interpreter.conformance

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.name
import kotlin.io.path.readText

/**
 * JVM FS shim actual — reads the `corpus/cases` tree with `java.nio`. The directory comes from `CORPUS_DIR` (Gradle-set);
 * an IDE run with no env falls back to walking up to the repo root (`settings.gradle`) and resolving `corpus/cases`.
 */
actual fun readCorpusFiles(): List<RawCorpusFile> {
    val dir = corpusCasesDir() ?: return emptyList()
    if (!Files.exists(dir)) return emptyList()
    Files.walk(dir).use { stream ->
        return stream.filter { Files.isRegularFile(it) && it.name.endsWith(".json") }
            .map { RawCorpusFile(it.parent.name, it.name.removeSuffix(".json"), it.readText()) }
            .toList()
            .sortedBy { "${it.family}/${it.id}" }
    }
}

private fun corpusCasesDir(): Path? {
    System.getenv("CORPUS_DIR")?.let { return Paths.get(it) }
    // IDE fallback: walk up to the repo root (the dir carrying settings.gradle) and resolve corpus/cases.
    var d: Path = Paths.get(System.getProperty("user.dir")).toAbsolutePath()
    while (d.parent != null && !Files.exists(d.resolve("settings.gradle"))) d = d.parent
    val cases = d.resolve("corpus").resolve("cases")
    return if (Files.exists(cases)) cases else null
}
