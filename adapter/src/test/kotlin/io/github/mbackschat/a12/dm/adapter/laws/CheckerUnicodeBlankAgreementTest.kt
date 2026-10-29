package io.github.mbackschat.a12.dm.adapter.laws

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import io.github.mbackschat.a12.dm.adapter.KernelConsistencyOracle
import io.github.mbackschat.a12.dm.core.ConsistencyCheckFailedException
import io.github.mbackschat.a12.dm.core.ModelInputException
import io.github.mbackschat.a12.dm.core.ReferenceResolvers
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Whether the checker's presence and trimming tests read a member the way A12 does, on the spaces that separate Java's
 * whitespace from Kotlin's (KERNEL-FINDINGS KF310): the no-break space is no Java whitespace and the em space is, while
 * Kotlin counts both. Each row writes one of them where a checker test asks whether a member is blank or strips it —
 * the condition language, a condition, a computation's precondition, a message parameter, and a stored count.
 *
 * Own domain (an insurance policy and its premium), built by editing the drafted model's JSON, because the typed
 * builder writes none of these spellings.
 */
class CheckerUnicodeBlankAgreementTest {

    @Test
    fun theCheckerReadsEachMemberAsA12Does() {
        val observed = ROWS.mapValues { (_, edit) -> model(edit).let { kernel(it) to ours(it) } }
        assertEquals(observed.mapValues { it.value.first to it.value.first }, observed, "each row as (A12, ours)")
    }

    private fun model(edit: (ObjectNode) -> Unit): String {
        val tree = mapper.readTree(BASE) as ObjectNode
        edit(tree)
        return mapper.writeValueAsString(tree)
    }

    private fun kernel(json: String): List<String> = try {
        oracle.checkConsistency(json, ReferenceResolvers.selfContained())
    } catch (unbuildable: ConsistencyCheckFailedException) {
        return listOf("CK_MODEL_NOT_BUILDABLE")
    } catch (unreadable: ModelInputException) {
        return listOf("CK_MODEL_NOT_BUILDABLE")
    }.map { notification ->
        val message = notification.message().trim()
        legibleNotificationClass(message)
    }.sorted()

    private fun ours(json: String): List<String> =
        rejectingCodes(json).sorted()

    private val oracle = KernelConsistencyOracle()

    private companion object {
        val mapper = ObjectMapper()
        const val NBSP = "\u00A0"
        const val EM = "\u2003"
        val BASE: String = DocumentModelDraft.named("policy-blanks").locale("en_US")
            .rootGroup("Policy") { p -> p.stringField("Reference") { }.stringField("Note") { }
                .group("Covers") { c -> c.repeatable(3).stringField("Kind") { } } }
            .build().toJson().let { json ->
                RuleValidator().inject(json, DraftRule("/Policy", "Probe", "../Reference", "FieldFilled(Reference)", "C1", "ERROR"))
            }

        fun ObjectNode.policy(): ArrayNode = at("/content/modelRoot/rootGroups/0/Group/elements") as ArrayNode
        fun ObjectNode.rule(): ObjectNode =
            policy().first { it.path("name").asText() == "Probe" }.path("Rule") as ObjectNode
        fun ObjectNode.covers(): ObjectNode = policy().first { it.path("name").asText() == "Covers" }.path("Group") as ObjectNode
        fun ObjectNode.computation(precondition: String) {
            val body = policy().addObject().put("type", "Computation").put("name", "Fill").put("id", "/Policy/Fill")
                .putObject("Computation").put("computedFieldRelPath", "../Note").put("commonPrecondition", precondition)
            body.putArray("computationAlternatives").addObject().put("precondition", "FieldFilled(Reference)")
                .put("operation", "\"standard\"")
            body.putArray("errorMessage").addObject().put("locale", "en_US").put("text", "Note from the reference")
        }
        fun ObjectNode.message(text: String) {
            (rule().path("errorMessage") as ArrayNode).removeAll().addObject().put("locale", "en_US").put("text", text)
        }

        val ROWS: Map<String, (ObjectNode) -> Unit> = linkedMapOf(
            "control" to { _ -> },
            "a no-break-space condition language" to { m ->
                (m.at("/content") as ObjectNode).with("modelConfig").putObject("conditionLanguage").put("code", NBSP)
            },
            "an em-space condition language" to { m ->
                (m.at("/content") as ObjectNode).with("modelConfig").putObject("conditionLanguage").put("code", EM)
            },
            "an empty condition language" to { m ->
                (m.at("/content") as ObjectNode).with("modelConfig").putObject("conditionLanguage").put("code", "")
            },
            "a space condition language" to { m ->
                (m.at("/content") as ObjectNode).with("modelConfig").putObject("conditionLanguage").put("code", " ")
            },
            "a padded condition language" to { m ->
                (m.at("/content") as ObjectNode).with("modelConfig").putObject("conditionLanguage").put("code", " en_US")
            },
            "a no-break-space condition" to { m -> m.rule().put("errorCondition", NBSP) },
            "an em-space condition" to { m -> m.rule().put("errorCondition", EM) },
            "a no-break-space common precondition" to { m -> m.computation(NBSP) },
            "an em-space common precondition" to { m -> m.computation(EM) },
            "a no-break-space message parameter" to { m -> m.message("Reference \$$NBSP\$ is due") },
            "an em-space message parameter" to { m -> m.message("Reference \$$EM\$ is due") },
            "a space message parameter" to { m -> m.message("Reference \$ \$ is due") },
            "a count padded with spaces" to { m -> m.covers().put("repeatability", " 3 ") },
            "a count padded with a no-break space" to { m -> m.covers().put("repeatability", "${NBSP}3") },
            "a count padded with an em space" to { m -> m.covers().put("repeatability", "3$EM") },
            "a zero count padded with spaces" to { m -> m.covers().put("repeatability", " 0 ") },
            "a count that is no number" to { m -> m.covers().put("repeatability", "three") },
        )
    }
}
