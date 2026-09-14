package io.github.mbackschat.a12.dm.adapter.laws

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import io.github.mbackschat.a12.dm.consistency.FindingCategory
import io.github.mbackschat.a12.dm.consistency.ModelSource
import io.github.mbackschat.a12.dm.consistency.check
import io.github.mbackschat.a12.dm.core.ModelConsistencyException
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.io.path.readText

/**
 * On a model A12 REJECTS, every A12 class the checker names must be one A12 named too.
 *
 * **This is the half the silence sweeps cannot reach, and it catches a different defect.**
 * [CheckerSilenceOnCleanModelsTest] and [CheckerSilenceOnMutatedModelsTest] both measure models A12 ACCEPTS,
 * so a checker that refuses nothing legal passes them however wrongly it classifies what it does refuse.
 * Naming the wrong class on a correctly rejected model is not an over-rejection — but it hands the author a
 * fix for a different gate, which is the same cost one step later.
 *
 * **It exists because the defect was real and recent.** On 2026-09-14 the reference family named
 * `MVK_ERROR_FIELD_NOT_REFERENCED` on seven committed models where A12 names `MVK_NO_WILDCARD`
 * ([KERNEL-FINDINGS KF350]), and it was found by exactly this comparison run by hand. A found defect whose
 * instrument is then thrown away is a defect class left open.
 *
 * **The population is mutants A12 rejects WITH a keyed class.** A12 answers some invalid models by refusing
 * to build them at all, reporting no class — the comparison is undefined there, so those are excluded rather
 * than counted as agreement. The mutations are chosen to reach the CONDITION gates, because damaging a
 * declaration mostly makes a model unbuildable and lands in that excluded tier — measured, not assumed: a
 * kind that removes a group's repetitions drew a keyed class from A12 **zero** times over the whole
 * estate and was dropped, which is what the per-kind assertion exists to catch. 32 mutants compare as
 * of 2026-09-14, 22 from the repoint and 10 from the unstar.
 *
 * **A12 masking one class behind another is not automatically a defect, which is why [masked] exists.** A12
 * stops at the first gate that answers; the checker accumulates. A class the checker reports and A12 does not
 * may be a true second defect A12 has not reached — or a wrong class. Only measurement separates them, so a
 * new pair fails this test and is dispositioned by hand rather than allowed through.
 */
class CheckerClassAgreementTest {

    /**
     * Pairs measured to be A12 MASKING a class the checker legitimately reports — not defects.
     *
     * Empty on purpose as of 2026-09-14: the one pair this test was built from turned out to be a defect in
     * the checker, and fixing it emptied the map. A row here must name the model, both classes, and the
     * measurement that says the checker's class is true of the model.
     */
    private val masked = emptySet<String>()

    /** Mutants A12 must reject with a keyed class before a green here means anything. */
    private val floor = 20

    @Test
    fun everyClassTheCheckerNamesOnARejectedModelIsOneTheKernelNames() {
        var compared = 0
        var noKeyedClass = 0
        val perKind = MUTATIONS.associate { it.label to 0 }.toMutableMap()
        val disagreements = mutableListOf<String>()
        for (model in committedModels()) {
            val text = model.readText()
            for (mutation in MUTATIONS) {
                val mutated = mutation.applyTo(text) ?: continue
                val kernel = kernelClasses(mutated) ?: continue
                if (kernel.isEmpty()) {
                    noKeyedClass++
                    continue
                }
                compared++
                perKind[mutation.label] = perKind.getValue(mutation.label) + 1
                val ours = check(ModelSource(mutated, diagnosticLabel = model.name)).findings
                    .filter { it.justifiesRejection && it.category == FindingCategory.A12_CONSTRAINT }
                    .map { it.code }.toSet()
                (ours - kernel).forEach { extra ->
                    val row = "${model.name}[${mutation.label}]: ours=$extra kernel=$kernel"
                    if (row !in masked) disagreements += row
                }
            }
        }

        assertTrue(
            compared >= floor,
            "only $compared mutants drew a keyed class from A12 (floor $floor; $noKeyedClass were refused " +
                "without one), so a green here would be an empty comparison rather than an agreeing one",
        )
        assertEquals(
            emptyList<String>(), perKind.filterValues { it == 0 }.keys.toList(),
            "a mutation kind drew no keyed class from A12, so it compares nothing and contributes only " +
                "runtime — fix the mutation rather than leaving it in: $perKind",
        )
        assertEquals(
            emptyList<String>(), disagreements,
            "the checker named an A12 class on a rejected model that A12 itself did not name. Either the " +
                "class is wrong — name the defect A12 names — or A12 is MASKING a true second defect behind " +
                "an earlier gate, which is a row in `masked` with the measurement that says so:\n" +
                disagreements.joinToString("\n") { "  $it" },
        )
    }

    /** The classes A12 reports for a model, or null when it BUILDS the model (not in this population). */
    private fun kernelClasses(json: String): Set<String>? = try {
        BoundModel.ofJson(json)
        null
    } catch (rejected: ModelConsistencyException) {
        rejected.notifications().mapNotNull { CLASS_KEY.find(it.message().trim())?.groupValues?.get(1) }.toSet()
    } catch (raised: RuntimeException) {
        // A12 refusing by RAISING past every typed channel is a reporting defect of its own (KF259) and says
        // nothing about classification, so it is not this test's population either.
        null
    }

    /** One perturbation aimed at the CONDITION gates, where A12 reports classes rather than refusing to build. */
    private class Mutation(val label: String, private val mutate: (ObjectNode) -> Boolean) {
        fun applyTo(text: String): String? {
            val root = runCatching { MAPPER.readTree(text) as? ObjectNode }.getOrNull() ?: return null
            return if (mutate(root)) MAPPER.writeValueAsString(root) else null
        }
    }

    private companion object {
        private val MAPPER = ObjectMapper()

        /** A12's own class key, the terminal `[KEY]` every coded notification carries. */
        private val CLASS_KEY = Regex(".*\\[(\\w+)]$")

        private fun groupBodies(root: ObjectNode): List<ObjectNode> {
            val found = mutableListOf<ObjectNode>()
            fun walk(node: ObjectNode) {
                (node["Group"] as? ObjectNode)?.let { found += it }
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

        private val MUTATIONS = listOf(
            // The mutation that found KF350: a target the condition cannot be naming, in the rule's own
            // group, leaving the condition untouched. It reaches the error-target gates, which is where the
            // checker and A12 have the most to disagree about.
            Mutation("repoint-error-field") { root ->
                val group = groupBodies(root).firstOrNull { body ->
                    (body["elements"] as? ArrayNode)?.any { it["type"]?.asText() == "Rule" } == true
                } ?: return@Mutation false
                val elements = group["elements"] as ArrayNode
                val field = MAPPER.createObjectNode()
                field.put("type", "Field").put("id", "mutant-target-1").put("name", "MutantTarget")
                field.putObject("Field").putObject("fieldType").put("type", "StringType")
                elements.add(field)
                val rule = (0 until elements.size()).first { elements[it]["type"]?.asText() == "Rule" }
                ((elements[rule] as ObjectNode)["Rule"] as ObjectNode)
                    .put("errorEntityRelPath", "../MutantTarget")
                true
            },
            // Remove one star from a rule's condition. A legal aggregate read becomes an iterating per-row
            // read, which is precisely the gate KF350 turned on — so this is the mutation aimed at the class
            // of defect this test exists for, rather than at models in general.
            Mutation("unstar-a-condition") { root ->
                val group = groupBodies(root).firstOrNull { body ->
                    (body["elements"] as? ArrayNode)?.any { element ->
                        element["type"]?.asText() == "Rule" &&
                            (element["Rule"] as? ObjectNode)?.get("errorCondition")?.asText()
                                ?.contains('*') == true
                    } == true
                } ?: return@Mutation false
                val elements = group["elements"] as ArrayNode
                val at = (0 until elements.size()).first { position ->
                    elements[position]["type"]?.asText() == "Rule" &&
                        (elements[position]["Rule"] as? ObjectNode)?.get("errorCondition")?.asText()
                            ?.contains('*') == true
                }
                val rule = elements[at]["Rule"] as ObjectNode
                rule.put("errorCondition", rule["errorCondition"].asText().replaceFirst("*", ""))
                true
            },
        )

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
