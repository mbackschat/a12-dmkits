package io.github.mbackschat.a12.dm.adapter.laws

import com.fasterxml.jackson.databind.ObjectMapper
import io.github.mbackschat.a12.dm.consistency.ConsistencyWorkspace
import io.github.mbackschat.a12.dm.consistency.ModelSource
import io.github.mbackschat.a12.dm.consistency.check
import io.github.mbackschat.a12.dm.core.ReferenceResolvers
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
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
 * **Both resolution paths are swept, because only one of them is the checker's own.** Three arms hand it a
 * model the KERNEL expanded, which removes the step a Kernel-free consumer cannot remove; the fourth hands it
 * the unexpanded model plus its sibling sources and makes it mount them itself.
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

    private companion object {
        private val MAPPER = ObjectMapper()
    }

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

    /**
     * The same law over the reference corpus — models nobody here wrote, which the committed trees cannot be.
     *
     * Our own fixtures were authored against the checker's own reading of A12; that shared origin is the
     * confound the committed arm cannot remove, however many models it holds. These were written by A12's own
     * team for A12's own kernel. Each is expanded by the Kernel first, so includes and imported typedefs are
     * folded in and the checker sees the self-contained form the Kernel itself validated.
     *
     * **Every stored model, not one canonical pick per directory.** The seeding sweeps take the canonical one
     * because an instance run over a near-duplicate costs minutes for nothing; this one is structural and the
     * whole tree costs seconds, so it holds the same population the parser sweep does — 59 models — rather
     * than a quarter of it that could drift from it silently.
     *
     * Never committed, EUPL, and skipped when the sibling clone is absent — the estate's standing rule for
     * both real corpora.
     */
    @Test
    fun noFamilyReportsOnTheReferenceCorpus() {
        val root = ParserCorpora.kernelMm()
        assumeTrue(root != null, "a12-kernel-mm not present — skipping the checker's corpus silence sweep")
        sweepCorpus("kernel-mm", root!!, CorpusShrinkSupport.documentModels(root))
    }

    /** The same, over the A12 Tools workspaces, whose mount path is machine-local and so supplied as a property. */
    @Test
    fun noFamilyReportsOnTheWorkspacesCorpus() {
        val root = ParserCorpora.workspaces()
        assumeTrue(root != null, "a12.corpus1.dir not set / absent — skipping the workspaces silence sweep")
        sweepCorpus("workspaces", root!!, CorpusShrinkSupport.documentModels(root))
    }

    /**
     * Expand each model through the Kernel, keep the ones it then BUILDS, and require the checker's silence.
     *
     * A model that fails to expand or to build is not measured and not counted: the population is "models the
     * Kernel accepts", and a preparation failure says nothing about the checker. `swept` rather than the file
     * count carries the vacuity assertion for exactly that reason — every exclusion above it drains the
     * population silently, and an empty corpus directory looks identical to a corpus that passes.
     */
    private fun sweepCorpus(label: String, root: Path, files: List<Path>) {
        val resolver = ReferenceResolvers.directory(root)
        val offenders = mutableListOf<String>()
        var swept = 0
        for (file in files) {
            val expanded = runCatching {
                CorpusModelPreparer.prepareKernelJson(file.readText(), resolver)
            }.getOrNull() ?: continue
            if (runCatching { BoundModel.ofJson(expanded) }.isFailure) continue
            swept++
            check(ModelSource(expanded, diagnosticLabel = file.name)).findings
                .filter { it.justifiesRejection }
                .forEach { offenders += "${file.name}: ${it.code} at ${it.at} — ${it.explain.take(160)}" }
        }
        println(
            "[checker-silence-sweep] $label: ${files.size} models, $swept Kernel-accepted, " +
                "${offenders.size} constraint finding(s)",
        )
        assertTrue(swept > 0) {
            "$label put no model through the checker at all — the sweep would pass vacuously"
        }
        assertTrue(offenders.isEmpty()) {
            "$label: the checker reported ${offenders.size} A12 constraint(s) violated on models the Kernel " +
                "BUILDS, which is the over-rejection direction this project forbids:\n" +
                offenders.take(25).joinToString("\n") { "  $it" }
        }
    }

    /**
     * The same demand with the CHECKER resolving the includes, which no other arm reaches.
     *
     * Every sweep above hands the checker a model the KERNEL already expanded, so `ConsistencyWorkspace` —
     * the checker's own mount resolution, the path a TypeScript or browser consumer has no Kernel to stand in
     * for — had never met a real model at all. A defect there is invisible to the other arms by construction,
     * because they remove the very step it owns.
     *
     * **The vacuity trap here is specific and sharp: an unexpanded mount returns a report with NO findings**,
     * which is indistinguishable from silence — and the status cannot tell them apart, since a clean report
     * is `Inconclusive` as well. `checksCompleted` can: it is empty on that early return alone. So it is
     * asserted before the findings are, and a `purpose: "include"` entry is required in the population,
     * because a typedef import on its own exercises no group mount.
     */
    @Test
    fun noFamilyReportsWhenTheCheckerMountsTheIncludesItself() {
        val providersById = committedModels().groupBy { modelIdOf(it) }
        val offenders = mutableListOf<String>()
        val unresolved = mutableListOf<String>()
        var swept = 0
        var mounts = 0
        for (entry in committedModels()) {
            val references = includeReferences(entry)
            if (references.isEmpty()) {
                continue
            }
            // An ambiguous or absent provider is not a checker defect, so such an entry leaves the
            // population rather than being asserted about — the same rule the Kernel-picked arms use.
            val providers = references.mapNotNull { providersById[it.reference]?.singleOrNull() }
            val entryId = modelIdOf(entry)
            if (providers.size != references.size || entryId == null) {
                continue
            }
            val expanded = runCatching {
                CorpusModelPreparer.prepareKernelJson(entry.readText(), ReferenceResolvers.directory(entry.parent))
            }.getOrNull() ?: continue
            if (runCatching { BoundModel.ofJson(expanded) }.isFailure) {
                continue
            }
            swept++
            mounts += references.count { it.purpose == "include" }
            val report = ConsistencyWorkspace
                .fromSources((listOf(entry) + providers).map { ModelSource(it.readText(), diagnosticLabel = it.name) })
                .check(entryId)
            // An unexpanded mount returns before any family runs, so `checksCompleted` is empty there and
            // non-empty on every other path. The STATUS cannot tell them apart — a clean report is
            // `Inconclusive` too, because coverage is incomplete until every required family exists.
            if (report.checksCompleted.isEmpty()) {
                unresolved += "${entry.name}: " +
                    report.gaps.filter { "not expanded here" in it.reason }.joinToString { "${it.at}: ${it.reason}" }
                continue
            }
            report.findings.filter { it.justifiesRejection }.forEach {
                offenders += "${entry.name}: ${it.code} at ${it.at} — ${it.explain.take(160)}"
            }
        }
        println("[checker-silence-workspace] $swept including models, $mounts group mount(s), ${offenders.size} finding(s)")

        assertTrue(swept > 0) {
            "no committed model with an include reached the workspace path, so this sweep is vacuous"
        }
        assertTrue(mounts > 0) {
            "every including model swept imports typedefs only, so no group mount was expanded and the " +
                "workspace's own resolution went unexercised"
        }
        assertTrue(unresolved.isEmpty()) {
            "the checker could not mount an include the Kernel resolves, so it completed no family at all — " +
                "a report with no findings, which would otherwise read as silence:\n" +
                unresolved.joinToString("\n") { "  $it" }
        }
        assertTrue(offenders.isEmpty()) {
            "the checker reported an A12 constraint as violated on a model the Kernel BUILDS, reached through " +
                "its OWN include resolution rather than the Kernel's expansion:\n" +
                offenders.joinToString("\n") { "  $it" }
        }
    }

    /** Whether the Kernel builds this model — `BoundModel.ofJson` is `checkConsistency` with no ERROR. */
    private fun kernelBuilds(path: Path): Boolean = runCatching { BoundModel.ofJson(path.readText()) }.isSuccess

    /** One `header.modelReferences` entry — the id it names and why the model names it. */
    private data class Included(val reference: String, val purpose: String)

    private fun includeReferences(path: Path): List<Included> =
        MAPPER.readTree(path.readText()).path("header").path("modelReferences")
            .mapNotNull { reference ->
                reference.path("reference").asText(null)?.let { Included(it, reference.path("purpose").asText("")) }
            }

    private fun modelIdOf(path: Path): String? = MAPPER.readTree(path.readText()).path("header").path("id").asText(null)

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
