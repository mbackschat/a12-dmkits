package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.conformance.CaseJson
import io.github.mbackschat.a12.dm.interpreter.conformance.ConformanceCase
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.name
import kotlin.io.path.readText

/** Locates and reads the committed conformance corpus (the `corpus/cases` tree) from the repo root. */
object CorpusFiles {

    /** The repo root — walk up from the test working dir to the dir carrying `settings.gradle`. */
    fun repoRoot(): Path {
        var d: Path = Paths.get(System.getProperty("user.dir")).toAbsolutePath()
        while (d.parent != null && !Files.exists(d.resolve("settings.gradle"))) d = d.parent
        return d
    }

    /** The `corpus/cases` directory (may not exist before the first generation). */
    fun casesDir(): Path = repoRoot().resolve("corpus").resolve("cases")

    /** A committed case with its file path, for a runner's report. */
    data class CaseFile(val path: Path, val case: ConformanceCase)

    /** Every committed case, sorted by path (stable dynamic-test order); empty if the corpus is absent. */
    fun all(): List<CaseFile> {
        val dir = casesDir()
        if (!Files.exists(dir)) return emptyList()
        Files.walk(dir).use { stream ->
            return stream.filter { Files.isRegularFile(it) && it.name.endsWith(".json") }.sorted()
                .map { CaseFile(it, CaseJson.parse(it.readText())) }.toList()
        }
    }
}
