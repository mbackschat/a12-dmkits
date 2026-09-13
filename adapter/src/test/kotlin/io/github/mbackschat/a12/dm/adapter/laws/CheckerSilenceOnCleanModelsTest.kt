package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.consistency.ModelSource
import io.github.mbackschat.a12.dm.consistency.check
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.io.path.readText

/**
 * The Kernel-free checker must stay SILENT on every committed model the Kernel itself accepts.
 *
 * This is the checker's standing positive control, and the only one that measures the whole report rather
 * than one family's grid. Each family is locked against a hand-written fixture exercising the shape it
 * judges; nothing in that tier can see a family that fires on ORDINARY models, because a fixture written
 * beside an implementation shares whatever the implementation assumes. A real model is the one input neither
 * of them wrote.
 *
 * **The class it exists to catch, measured 2026-09-13.** The repetition-wildcard family read a DM-JSON member
 * A12 does not have (`repeatable` for `repeatability`); its own fixtures carried the same invention, so every
 * test it had passed while the family would have refused every correctly starred group in a real model. It
 * was caught by accident — a fixture written for an unrelated family happened to disagree. `MVK_INVALID_WILDCARD`
 * on a corpus model is exactly what this test would have printed on the first run.
 *
 * **The Kernel picks the population, so nothing here decides which models are "valid".** A model counts only
 * when {@code BoundModel.ofJson} builds it, which is `checkConsistency` with no ERROR — so a deliberately
 * broken fixture, or one whose includes need a resolver this test does not supply, simply drops out instead
 * of needing a hand-maintained exclusion list. The floor below is what keeps that from draining silently.
 *
 * **Over-rejection is the only direction measured.** Silence proves nothing about a family's reach, and is
 * not meant to: an under-reporting checker is safe by this project's rules and is what the per-family grids
 * are for.
 */
class CheckerSilenceOnCleanModelsTest {

    /**
     * How many committed models the Kernel must still build for this sweep to mean anything.
     *
     * Deliberately far below the live count — it answers "did the sweep reach real models", never "how many
     * models does the estate hold", so it does not move when the estate grows.
     */
    private val floor = 15

    @Test
    fun noFamilyReportsOnAModelTheKernelAccepts() {
        val census = committedModels().map { it to kernelBuilds(it) }
        val clean = census.filter { (_, builds) -> builds }.map { (path, _) -> path }

        assertTrue(
            clean.size >= floor,
            "the sweep reached only ${clean.size} Kernel-accepted models (floor $floor) out of " +
                "${census.size} committed files, so a green here would be an empty sweep rather than a " +
                "clean one. Either the model trees moved or BoundModel.ofJson started refusing them: " +
                census.filterNot { it.second }.joinToString { it.first.name },
        )

        val offenders = clean.associateWith { path ->
            check(ModelSource(path.readText(), diagnosticLabel = path.name)).findings
                .filter { it.justifiesRejection }
        }.filterValues { it.isNotEmpty() }

        assertEquals(
            emptyMap<Path, Any>(), offenders,
            "the checker reported an A12 constraint as violated on a model the Kernel BUILDS, which is the " +
                "over-rejection direction this project forbids. Each entry names the model and the finding; " +
                "the family that produced the code is the one to re-measure, not the model to change:\n" +
                offenders.entries.joinToString("\n") { (path, findings) ->
                    "  ${path.name}: " + findings.joinToString { "${it.code} at ${it.at}" }
                },
        )
    }

    /** Whether the Kernel builds this model — `BoundModel.ofJson` is `checkConsistency` with no ERROR. */
    private fun kernelBuilds(path: Path): Boolean = runCatching { BoundModel.ofJson(path.readText()) }.isSuccess

    /**
     * Every committed DM-JSON model, from the three trees that hold one.
     *
     * The conformance corpus is the richest of the three by construction — its models are standalone, so the
     * Kernel builds them without a resolver — and the kits' and CLI's fixture trees add the shapes those
     * suites needed. A tree that disappears is caught by the floor rather than by a path assertion here.
     */
    private fun committedModels(): List<Path> {
        val root = CorpusFiles.repoRoot()
        return listOf(
            root.resolve("corpus/models"),
            root.resolve("cli/src/test/resources/models"),
            root.resolve("src/test/resources/models"),
        ).filter { Files.isDirectory(it) }.flatMap { dir ->
            Files.list(dir).use { stream ->
                stream.filter { Files.isRegularFile(it) && it.name.endsWith(".json") }.sorted().toList()
            }
        }
    }
}
