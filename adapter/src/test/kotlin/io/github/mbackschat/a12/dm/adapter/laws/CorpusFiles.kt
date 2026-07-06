package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.conformance.CaseJson
import io.github.mbackschat.a12.dm.interpreter.conformance.ConformanceCase
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.name
import kotlin.io.path.readText

/** Locates and reads the committed conformance corpus (the `corpus/cases` tree + shared `corpus/models`) from the repo root. */
object CorpusFiles {

    /** The repo root — walk up from the test working dir to the dir carrying `settings.gradle`. */
    fun repoRoot(): Path {
        var d: Path = Paths.get(System.getProperty("user.dir")).toAbsolutePath()
        while (d.parent != null && !Files.exists(d.resolve("settings.gradle"))) d = d.parent
        return d
    }

    /** The corpus root (`corpus/`), against which a case's `modelRef` resolves. */
    fun corpusRoot(): Path = repoRoot().resolve("corpus")

    /** The `corpus/cases` directory (may not exist before the first generation). */
    fun casesDir(): Path = corpusRoot().resolve("cases")

    /** The `corpus/models` directory of standalone DM-JSON model files (may not exist before the first generation). */
    fun modelsDir(): Path = corpusRoot().resolve("models")

    /** Resolve a case's [ConformanceCase.modelRef] against the corpus root; fails fast if it does not resolve. */
    fun resolveModel(modelRef: String): String {
        val file = corpusRoot().resolve(modelRef)
        require(Files.exists(file)) {
            "case modelRef '$modelRef' does not resolve — expected a corpus-root-relative model file " +
                "(e.g. \"models/<name>.json\") at $file"
        }
        return file.readText()
    }

    /** A committed case with its file path and its resolved model DM-JSON, for a runner's report. */
    data class CaseFile(val path: Path, val case: ConformanceCase, val modelJson: String)

    /** Every committed case (model resolved), sorted by path (stable dynamic-test order); empty if the corpus is absent. */
    fun all(): List<CaseFile> {
        val dir = casesDir()
        if (!Files.exists(dir)) return emptyList()
        Files.walk(dir).use { stream ->
            return stream.filter { Files.isRegularFile(it) && it.name.endsWith(".json") }.sorted()
                .map { val case = CaseJson.parse(it.readText()); CaseFile(it, case, resolveModel(case.modelRef)) }.toList()
        }
    }
}
