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
 * On a model A12 REJECTS, every A12 class the checker names must be one A12 named — at the DECLARATION A12
 * named it about.
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
 * estate and was dropped, which is what the per-kind assertion exists to catch.
 *
 * **The error MESSAGE is a second position A12 classifies, and it was outside this population until
 * 2026-09-15.** Every kind aimed at a declaration or a condition leaves the message untouched, so the wrong
 * class that stood longest in this estate — the rule-group shorthand reported as a reference failure — could
 * not be reached by any of them. The three message kinds also reach far more of the estate, because a rule
 * with a message is nearly every model while a rule whose message writes a PARAMETER is four of forty-three:
 * 98 mutants compare, up from 32 (measured 2026-09-15), and none is refused without a keyed class.
 *
 * **A12 masking one class behind another is not automatically a defect, which is why [masked] exists.** A12
 * stops at the first gate that answers; the checker accumulates. A class the checker reports and A12 does not
 * may be a true second defect A12 has not reached — or a wrong class. Only measurement separates them, so a
 * new pair fails this test and is dispositioned by hand rather than allowed through.
 *
 * **The comparison is keyed by DECLARATION, which is what makes the right class in the wrong place visible.**
 * A12 prefixes every keyed notification of this population with the declaration it judged — measured
 * 2026-09-15 as `<Kind> with path '<path>': … [KEY]` on all 98, every one of them keyed, every one of them
 * prefixed and every one naming a `Rule`; the earlier assumption that the prefix carries an element ID was
 * wrong, so no id→path map is needed and the paths compare directly against a finding's own `at`. A class-only
 * comparison passes a checker that names A12's own class about a declaration A12 said nothing about, and on
 * this population it cannot even see a SECOND class, because A12 answers each of these mutants with exactly
 * one notification (98 of 98) — so `ours ⊆ kernel` already forces a one-element set and the count invariant
 * the F2 record asked for is only meaningful once the key is the declaration.
 *
 * **Only declarations A12 keyed are compared, and that restriction is the honest half.** A12 stops at its
 * first answering gate, so its silence about every OTHER declaration of the mutant is not evidence: comparing
 * there would refuse honest content the moment A12's stop point moved. What the restriction costs is a
 * finding whose class A12 named about a declaration A12 never reached, which is why the classes A12 named are
 * compared as (declaration, class) PAIRS rather than per declaration — a class A12 named is then wrong
 * wherever A12 did not name it, and a genuine second instance A12 masked is a row in [positionsA12Masks].
 *
 * **Where the checker is SILENT on a rejected mutant it is under-reporting, which is the safe direction and
 * is disclosed rather than asserted**: 16 of the 98 as of 2026-09-15. It is not left unguarded — the
 * [positionedFloor] is what fails if that number climbs, because a compared pair needs both sides to speak.
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

    /**
     * Rows measured to be a class A12 named about ONE declaration that is genuinely also true of another.
     *
     * Empty as of 2026-09-15: every one of the checker's 82 positioned findings over this population landed
     * at the declaration A12 keyed. A row here must name the model, the mutation, both declarations, and the
     * measurement that says the checker's placement is true of the model — not merely plausible.
     */
    private val positionsA12Masks = emptySet<String>()

    /** Mutants A12 must reject with a keyed class before a green here means anything. */
    private val floor = 20

    /**
     * (mutant, declaration) pairs where A12 keyed a class AND the checker names something there.
     *
     * A pair needs both sides to speak, so this is the assertion that fails if the checker goes quiet on
     * rejected models — the direction the silence sweeps cannot see, since they measure models A12 BUILDS.
     * 82 of 98 as of 2026-09-15; the floor sits well below that on purpose, because it answers *did the
     * comparison happen* rather than *how big is the population*.
     */
    private val positionedFloor = 60

    @Test
    fun everyClassTheCheckerNamesOnARejectedModelIsOneTheKernelNames() {
        var compared = 0
        var noKeyedClass = 0
        var positionedPairs = 0
        var silentOnRejection = 0
        val perKind = MUTATIONS.associate { it.label to 0 }.toMutableMap()
        val disagreements = mutableListOf<String>()
        val misplaced = mutableListOf<String>()
        val repeated = mutableListOf<String>()
        val unpositioned = mutableListOf<String>()
        val unparsed = mutableListOf<String>()
        for (model in committedModels()) {
            val text = model.readText()
            for (mutation in MUTATIONS) {
                val mutated = mutation.applyTo(text) ?: continue
                val kernel = kernelClassification(mutated) ?: continue
                val where = { note: String -> "${model.name}[${mutation.label}]: $note" }
                unparsed += kernel.unparsed.map { where("A12 keyed `$it` with no declaration prefix") }
                if (kernel.classes.isEmpty()) {
                    noKeyedClass++
                    continue
                }
                compared++
                perKind[mutation.label] = perKind.getValue(mutation.label) + 1
                val ours = check(ModelSource(mutated, diagnosticLabel = model.name)).findings
                    .filter { it.justifiesRejection && it.category == FindingCategory.A12_CONSTRAINT }
                if (ours.isEmpty()) silentOnRejection++
                (ours.map { it.code }.toSet() - kernel.classes).forEach { extra ->
                    val row = where("ours=$extra kernel=${kernel.classes}")
                    if (row !in masked) disagreements += row
                }
                ours.filter { it.at == null }.forEach { finding ->
                    unpositioned += where("${finding.code} names no declaration, so it cannot be compared")
                }
                val positioned = ours.filter { it.at != null }
                positioned.groupBy { it.at to it.code }.filterValues { it.size > 1 }.forEach { (key, found) ->
                    repeated += where("${found.size} × ${key.second} at ${key.first}")
                }
                // Only the classes A12 actually named: a class A12 names nowhere is the row above, and
                // reporting it twice would make one defect read as two.
                positioned.filter { it.code in kernel.classes }
                    .filterNot { kernel.byDeclaration[it.at]?.contains(it.code) == true }
                    .forEach { finding ->
                        val row = where(
                            "${finding.code} at ${finding.at}, where A12 named it at " +
                                kernel.byDeclaration.filterValues { finding.code in it }.keys.sorted(),
                        )
                        if (row !in positionsA12Masks) misplaced += row
                    }
                positionedPairs += kernel.byDeclaration.keys.count { at -> positioned.any { it.at == at } }
            }
        }

        assertEquals(
            emptyList<String>(), unparsed,
            "A12 keyed a class in a shape this comparison cannot read, so the declaration it judged was " +
                "silently dropped and the keying below degraded to a class-only check. Re-measure the " +
                "notification prefix and widen the parser rather than letting it fail open:\n" +
                unparsed.joinToString("\n") { "  $it" },
        )
        assertTrue(
            compared >= floor,
            "only $compared mutants drew a keyed class from A12 (floor $floor; $noKeyedClass were refused " +
                "without one), so a green here would be an empty comparison rather than an agreeing one",
        )
        assertTrue(
            positionedPairs >= positionedFloor,
            "only $positionedPairs (mutant, declaration) pairs had something on BOTH sides (floor " +
                "$positionedFloor), with the checker silent on $silentOnRejection of $compared rejected " +
                "mutants — so the positional half below compared almost nothing",
        )
        assertEquals(
            emptyList<String>(), perKind.filterValues { it == 0 }.keys.toList(),
            "a mutation kind drew no keyed class from A12, so it compares nothing and contributes only " +
                "runtime — fix the mutation rather than leaving it in: $perKind",
        )
        assertEquals(
            emptyList<String>(), unpositioned,
            "a finding that alone rejects the model named no declaration, so an author cannot act on it " +
                "and this comparison cannot place it. Give it the declared path it concerns:\n" +
                unpositioned.joinToString("\n") { "  $it" },
        )
        assertEquals(
            emptyList<String>(), repeated,
            "the checker reported one class twice about one declaration, which reads to an author as two " +
                "defects where A12 names one:\n" + repeated.joinToString("\n") { "  $it" },
        )
        assertEquals(
            emptyList<String>(), disagreements,
            "the checker named an A12 class on a rejected model that A12 itself did not name. Either the " +
                "class is wrong — name the defect A12 names — or A12 is MASKING a true second defect behind " +
                "an earlier gate, which is a row in `masked` with the measurement that says so:\n" +
                disagreements.joinToString("\n") { "  $it" },
        )
        assertEquals(
            emptyList<String>(), misplaced,
            "the checker named A12's own class about a declaration A12 named it about nowhere. The class " +
                "being right is what makes this invisible to the comparison above, and it still hands the " +
                "author the wrong element to fix — unless the second instance is genuinely true of the " +
                "model, which is a row in `positionsA12Masks` with its measurement:\n" +
                misplaced.joinToString("\n") { "  $it" },
        )
    }

    /**
     * What A12 says about a model, keyed by the declaration it says it about.
     *
     * @param unparsed classes A12 keyed whose declaration prefix this reader could not bind, held separately
     *     so a changed notification shape fails loudly instead of quietly emptying the keying.
     */
    private class KernelClassification(
        val byDeclaration: Map<String, Set<String>>,
        val unparsed: List<String>,
    ) {
        val classes: Set<String> = byDeclaration.values.flatten().toSet()
    }

    /** What A12 reports for a model, or null when it BUILDS the model (not in this population). */
    private fun kernelClassification(json: String): KernelClassification? = try {
        BoundModel.ofJson(json)
        null
    } catch (rejected: ModelConsistencyException) {
        val keyed = rejected.notifications().mapNotNull { notification ->
            val message = notification.message().trim()
            // An UNKEYED notification carries prose rather than a class, so it is not a comparable claim at
            // all; only a keyed one that hides its declaration is a parser failure. Measured 2026-09-15:
            // every one of the 98 over this population is keyed, prefixed, and about a `Rule`.
            CLASS_KEY.find(message)?.groupValues?.get(1)?.let { key ->
                KEYED_DECLARATION.find(message)?.groupValues?.get(2) to key
            }
        }
        KernelClassification(
            byDeclaration = keyed.filter { it.first != null }
                .groupBy({ it.first!! }, { it.second })
                .mapValues { (_, keys) -> keys.toSet() },
            unparsed = keyed.filter { it.first == null }.map { it.second },
        )
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
        private val CLASS_KEY = Regex(".*\\[(\\w+)]$", RegexOption.DOT_MATCHES_ALL)

        /**
         * The declaration a keyed notification judges — A12's own leading `<Kind> with path '<path>':`.
         *
         * The kind word is captured and deliberately unused: a path identifies the declaration on its own,
         * and pinning the kind too would fail on a class A12 reports about a `Field` or a `Computation`
         * without measuring anything this comparison is about.
         */
        private val KEYED_DECLARATION = Regex("^(\\w+) with path '([^']*)':", RegexOption.DOT_MATCHES_ALL)

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

        /**
         * The first rule message text the model carries, as the entry that owns it.
         *
         * The message mutations all APPEND to an existing text rather than rewriting the declaration, which
         * is what keeps them one-variable: every declared element stays exactly as authored, so a class A12
         * draws on the mutant is the parameter's and the comparison is about the message families alone.
         */
        private fun firstRuleMessage(root: ObjectNode): ObjectNode? = groupBodies(root).asSequence()
            .mapNotNull { it["elements"] as? ArrayNode }
            .flatMap { elements -> elements.asSequence() }
            .filter { it["type"]?.asText() == "Rule" }
            .mapNotNull { (it["Rule"] as? ObjectNode)?.get("errorMessage") as? ArrayNode }
            .mapNotNull { it.firstOrNull() as? ObjectNode }
            .firstOrNull { it["text"]?.isTextual == true }

        /** One perturbation of a message TEXT — the position no declaration-shaped mutation reaches. */
        private fun messageMutation(label: String, appended: String) = Mutation(label) { root ->
            val message = firstRuleMessage(root) ?: return@Mutation false
            message.put("text", message["text"].asText() + appended)
            true
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
            // The three below aim at the error MESSAGE, which no declaration-shaped mutation reaches — the
            // blind spot that let a wrong class stand in the name position for as long as the family existed
            // (`$RuleGroup$` reported as `INVALID_ENTITY`, fixed 2026-09-15). They also reach far more of the
            // estate than the condition kinds do: every model carrying a rule with a message, rather than the
            // four that happen to write a parameter.
            messageMutation("name-an-unresolvable-parameter", " ${'$'}MutantParameter${'$'}"),
            messageMutation("unterminate-a-message", " ${'$'}Oops"),
            // The rule-group shorthand is a TERMINAL A12 recognises and refuses in this position, and only in
            // its own language — so this kind compares nothing on a German-condition model, which is what the
            // per-kind assertion tolerates as long as some model reaches it.
            messageMutation("name-the-rule-group-shorthand", " ${'$'}RuleGroup${'$'}"),
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
