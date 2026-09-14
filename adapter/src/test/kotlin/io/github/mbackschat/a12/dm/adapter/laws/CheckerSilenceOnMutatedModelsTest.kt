package io.github.mbackschat.a12.dm.adapter.laws

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
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
 * The checker must stay silent on every PERTURBED model A12 still builds, not only on the ones we authored.
 *
 * **The sibling sweep has a population problem, and this is it.** [CheckerSilenceOnCleanModelsTest] runs the
 * whole checker over every model A12 accepts — the committed trees plus both reference corpora — and that is
 * the project's central over-rejection guard. But every one of those models was written to be VALID, so a
 * family's negative path is exercised only where some author happened to write an unusual shape. The shapes
 * nearest a family's decision boundary are exactly the ones nobody wrote.
 *
 * **Two over-rejections on 2026-09-14 sat in that blind spot.** `REPETITION_SCOPE_GROUP` would have reported
 * the repeatability class on a scope naming a FIELD, and `FILTER_WILDCARD` did report the wildcard class on a
 * filter whose leaf resolves to nothing. Both were caught only because a mirror test happened to state the
 * declined row — a guard that depends on somebody writing the row is not a guard.
 *
 * **A12 arbitrates its own population, which is what makes this need no expectation files.** Each mutation is
 * applied mechanically and the result is offered to `BoundModel.ofJson`; a mutant A12 refuses is simply not in
 * the population, and one it accepts is a model the checker must not refuse either. So a mutation kind may be
 * as destructive as it likes — it costs coverage, never correctness.
 *
 * **What it adds, and what it does not — both measured on 2026-09-14 rather than argued.** Inverting
 * `RepetitionWildcard`'s repeatability test is killed here, so the sweep does reach real family logic through
 * models nobody wrote. Dropping `ErrorTargetIteration`'s repetition guard SURVIVES it and is caught by that
 * family's own mirror instead — so this is an additional net over the families, never a replacement for their
 * grids, and a green here says nothing about a family's reach.
 *
 * **Deliberately NOT compared: the CLASSES both engines draw on a rejected mutant.** A12 stops at its first
 * refusal while the checker accumulates, so the two have different denominators there and a subset assertion
 * would fail on honest reporting. That comparison is its own slice
 * ([`docs/PORTABLE-CONSISTENCY-IMPLEMENTATION-PLAN.md`](../../../../../../../../docs/PORTABLE-CONSISTENCY-IMPLEMENTATION-PLAN.md),
 * the F2 record).
 */
class CheckerSilenceOnMutatedModelsTest {

    /**
     * How many surviving mutants the sweep needs before a green here means anything.
     *
     * Far below the live count on purpose: it answers "did the sweep reach models A12 builds", never "how
     * many mutants does the estate produce", so it does not move when a model tree grows. 141 mutants
     * survived A12 when this was set (measured 2026-09-14).
     */
    private val floor = 40

    @Test
    fun noFamilyReportsOnAMutantTheKernelStillBuilds() {
        val survivors = mutableListOf<Mutant>()
        for (model in committedModels()) {
            val text = model.readText()
            for (mutation in MUTATIONS) {
                val mutated = mutation.applyTo(text) ?: continue
                if (runCatching { BoundModel.ofJson(mutated) }.isFailure) {
                    continue
                }
                survivors += Mutant(model.name, mutation.label, mutated)
            }
        }

        assertTrue(
            survivors.size >= floor,
            "only ${survivors.size} mutants survived A12 (floor $floor), so a green here would be an empty " +
                "sweep rather than a clean one — either the model trees moved or every mutation kind stopped " +
                "producing a buildable model",
        )
        val perKind = MUTATIONS.associate { it.label to survivors.count { m -> m.mutation == it.label } }
        assertEquals(
            emptyList<String>(), perKind.filterValues { it == 0 }.keys.toList(),
            "a mutation kind produced no model A12 builds, so it contributes nothing and its silence is " +
                "vacuous — fix the mutation rather than deleting the row: $perKind",
        )

        val offenders = survivors.mapNotNull { mutant ->
            val findings = check(ModelSource(mutant.json, diagnosticLabel = mutant.label()))
                .findings.filter { it.justifiesRejection }
            if (findings.isEmpty()) null else mutant.label() + ": " + findings.joinToString {
                "${it.code} at ${it.at} — ${it.explain.take(140)}"
            }
        }
        assertEquals(
            emptyList<String>(), offenders,
            "the checker reported an A12 constraint as violated on a PERTURBED model A12 still builds, which " +
                "is the over-rejection direction this project forbids. The family that produced the code is " +
                "the one to re-measure; the mutation only found the shape:\n" + offenders.joinToString("\n"),
        )
    }

    /**
     * The same demand over the reference corpus — models nobody here wrote, perturbed.
     *
     * This is the arm that removes both confounds at once. The committed trees were authored against this
     * project's own reading of A12, and every model anywhere was authored to be VALID; mutating a corpus
     * written by A12's own team for A12's own kernel removes the first and the mutation removes the second.
     *
     * Each model is expanded by the Kernel first, exactly as the clean sweep does, so the mutation applies to
     * the self-contained form. A mutant exists only for the length of the assertion and is never written —
     * the same terms on which the original is read.
     */
    @Test
    fun noFamilyReportsOnAMutatedReferenceCorpusModel() {
        val root = ParserCorpora.kernelMm()
        assumeTrue(root != null, "a12-kernel-mm not present — skipping the mutated corpus sweep")
        sweepCorpus("kernel-mm", root!!)
    }

    /** The same, over the A12 Tools workspaces, whose mount path is machine-local and supplied as a property. */
    @Test
    fun noFamilyReportsOnAMutatedWorkspacesModel() {
        val root = ParserCorpora.workspaces()
        assumeTrue(root != null, "a12.corpus1.dir not set / absent — skipping the mutated workspaces sweep")
        sweepCorpus("workspaces", root!!)
    }

    private fun sweepCorpus(label: String, root: Path) {
        val resolver = ReferenceResolvers.directory(root)
        val offenders = mutableListOf<String>()
        var swept = 0
        for (file in CorpusShrinkSupport.documentModels(root)) {
            val expanded = runCatching {
                CorpusModelPreparer.prepareKernelJson(file.readText(), resolver)
            }.getOrNull() ?: continue
            for (mutation in MUTATIONS) {
                val mutated = mutation.applyTo(expanded) ?: continue
                if (runCatching { BoundModel.ofJson(mutated) }.isFailure) continue
                swept++
                check(ModelSource(mutated, diagnosticLabel = "${file.name} [${mutation.label}]"))
                    .findings.filter { it.justifiesRejection }
                    .forEach { offenders += "${file.name} [${mutation.label}]: ${it.code} at ${it.at}" }
            }
        }
        assertTrue(swept > 0) {
            "$label produced no mutant the Kernel builds, so the sweep would pass vacuously"
        }
        assertTrue(offenders.isEmpty()) {
            "$label: the checker reported ${offenders.size} A12 constraint(s) on PERTURBED models the Kernel " +
                "still BUILDS, which is the over-rejection direction this project forbids:\n" +
                offenders.take(25).joinToString("\n") { "  $it" }
        }
    }

    private data class Mutant(val model: String, val mutation: String, val json: String) {
        fun label(): String = "$model [$mutation]"
    }

    /**
     * One mechanical perturbation of a stored model, or null when the model has nothing to perturb.
     *
     * Each is deterministic and picks the FIRST applicable site rather than a random one, so a failure names a
     * mutant anybody can rebuild. Randomness would widen the reach and cost the property that matters more
     * here: a red run points at one file and one edit.
     */
    private class Mutation(val label: String, private val mutate: (ObjectNode) -> Boolean) {
        fun applyTo(text: String): String? {
            val root = runCatching { MAPPER.readTree(text) as? ObjectNode }.getOrNull() ?: return null
            return if (mutate(root)) MAPPER.writeValueAsString(root) else null
        }
    }

    private companion object {
        private val MAPPER = ObjectMapper()

        /** Every `"<Kind>": { … }` body of a declared element, in document order, roots first. */
        private fun bodies(root: ObjectNode, kind: String): List<ObjectNode> {
            val found = mutableListOf<ObjectNode>()
            fun walk(node: ObjectNode) {
                (node[kind] as? ObjectNode)?.let { found += it }
                node.elements().forEach { child ->
                    when (child) {
                        is ObjectNode -> walk(child)
                        is ArrayNode -> child.forEach { if (it is ObjectNode) walk(it) }
                        else -> Unit
                    }
                }
            }
            walk(root)
            return found
        }

        private fun ObjectNode.elementsArray(): ArrayNode? = this["elements"] as? ArrayNode

        private val MUTATIONS = listOf(
            // A field nothing reads. The one mutation that should ALWAYS survive, so it doubles as the
            // sweep's own positive control: if this kind stops producing survivors, the harness is broken
            // rather than the estate.
            Mutation("add-unused-field") { root ->
                val group = bodies(root, "Group").firstOrNull { it.elementsArray() != null } ?: return@Mutation false
                val field = MAPPER.createObjectNode()
                field.put("type", "Field").put("id", "mutant-field-1").put("name", "MutantSpare")
                field.putObject("Field").putObject("fieldType").put("type", "StringType")
                group.elementsArray()!!.add(field)
                true
            },
            // Authored order is not semantic, so the model stays valid while every family that walks elements
            // meets them in an order no fixture uses.
            Mutation("reverse-element-order") { root ->
                val group = bodies(root, "Group").firstOrNull { (it.elementsArray()?.size() ?: 0) > 1 }
                    ?: return@Mutation false
                val elements = group.elementsArray()!!
                val reversed = MAPPER.createArrayNode().addAll(elements.reversed())
                group.set<ArrayNode>("elements", reversed)
                true
            },
            // A repetition cap is a bound, not a shape, so raising it cannot invalidate a rule — it moves the
            // one number several families read without changing what they should conclude.
            Mutation("raise-repeatability") { root ->
                val group = bodies(root, "Group").firstOrNull { it["repeatability"]?.isInt == true }
                    ?: return@Mutation false
                group.put("repeatability", group["repeatability"].asInt() + 1)
                true
            },
            // The one that flips a property half the families read. Most models will not survive it, which is
            // the point: the survivors are models whose rules happen to tolerate a new repetition level, and
            // no author wrote one.
            Mutation("make-group-repeatable") { root ->
                val group = bodies(root, "Group").firstOrNull {
                    it["repeatability"] == null && it.elementsArray() != null
                } ?: return@Mutation false
                group.put("repeatability", 3)
                true
            },
        )

        /** The same three committed trees the clean sweep reads; the reference corpora have their own arms. */
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
}
