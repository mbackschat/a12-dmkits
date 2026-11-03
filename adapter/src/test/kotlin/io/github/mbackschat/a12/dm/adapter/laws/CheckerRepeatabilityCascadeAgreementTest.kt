package io.github.mbackschat.a12.dm.adapter.laws

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import io.github.mbackschat.a12.dm.adapter.KernelConsistencyOracle
import io.github.mbackschat.a12.dm.consistency.ModelSource
import io.github.mbackschat.a12.dm.consistency.check
import io.github.mbackschat.a12.dm.core.ConsistencyCheckFailedException
import io.github.mbackschat.a12.dm.core.ModelInputException
import io.github.mbackschat.a12.dm.core.ReferenceResolvers
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.validate.DraftComputation
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Where A12 reports a group's non-positive `repeatability` (KERNEL-FINDINGS KF590), against the portable checker,
 * every class with its multiplicity and the path it attaches to.
 *
 * A12 derives each element's repetition layers from its ancestors, so the rival readings are "the group alone" and
 * "the group and everything beneath it"; a nested group and a field two levels down separate them, and a sibling
 * outside the group is the control. A rule and a computation declared in the group ask whether "everything" reaches
 * past groups and fields: A12's entity walk visits only those two (SPEC-2026-10-23-04). The member's own range sentence rides beside the class. Own domain (an
 * insurance policy's coverages and their riders), built by editing the drafted model's JSON, because the typed
 * builder refuses these counts.
 */
class CheckerRepeatabilityCascadeAgreementTest {

    @Test
    fun theCheckerReportsA12sClassesWhereA12Does() {
        val observed = ROWS.associateWith { row -> model(row).let { kernel(it) to ours(it) } }
        assertEquals(observed.mapValues { it.value.first to it.value.first }, observed, "each row as (A12, ours)")
    }

    /** A12's own answer on the row that separates the two readings, so an agreement on a broken fixture cannot pass. */
    @Test
    fun a12ReportsEveryGroupAndFieldBeneathTheGroup() {
        assertEquals(listOf(
            "$INVALID@/Policy/Coverages", "$INVALID@/Policy/Coverages/Amount", "$INVALID@/Policy/Coverages/Note",
            "$INVALID@/Policy/Coverages/Riders", "$INVALID@/Policy/Coverages/Riders/Code", "$NOT_POSITIVE@/Policy/Coverages",
        ), kernel(model(Row(coverages = mapper.valueToTree(0)))))
    }

    private data class Row(
        val coverages: com.fasterxml.jackson.databind.JsonNode? = null,
        val riders: com.fasterxml.jackson.databind.JsonNode? = null,
        val root: com.fasterxml.jackson.databind.JsonNode? = null,
    )

    private fun model(row: Row): String {
        val tree = mapper.readTree(BASE)
        val root = tree.at("/content/modelRoot/rootGroups/0/Group") as ObjectNode
        val coverages = tree.at("/content/modelRoot/rootGroups/0/Group/elements/0/Group") as ObjectNode
        val riders = coverages.at("/elements/1/Group") as ObjectNode
        row.root?.let { root.set<ObjectNode>("repeatability", it) }
        row.coverages?.let { coverages.set<ObjectNode>("repeatability", it) }
        row.riders?.let { riders.set<ObjectNode>("repeatability", it) }
        return mapper.writeValueAsString(tree)
    }

    private fun kernel(json: String): List<String> = try {
        oracle.checkConsistency(json, ReferenceResolvers.selfContained())
    } catch (unbuildable: ConsistencyCheckFailedException) {
        return listOf("CK_MODEL_NOT_BUILDABLE@/")
    } catch (unreadable: ModelInputException) {
        // Kernel 31 refuses an explicit `null` count at the reader, since the member is a primitive `int` (KF593).
        return listOf("CK_MODEL_NOT_BUILDABLE@/")
    }.map { notification ->
        val message = notification.message().trim()
        val at = ATTACHED.find(message)?.groupValues?.get(1) ?: "(unattached)"
        val code = legibleNotificationClass(message)
        "$code@$at"
    }.sorted()

    private fun ours(json: String): List<String> = check(ModelSource(json)).findings.filter { it.justifiesRejection }
        .map { if (it.code == "CK_MODEL_NOT_BUILDABLE") "${it.code}@/" else "${it.code}@${it.at}" }.sorted()

    private val oracle = KernelConsistencyOracle()

    private companion object {
        val mapper = ObjectMapper()
        const val INVALID = "MVK_MAX_VALUES_INVALID"
        const val NOT_POSITIVE = "RK_MEMBER_NOT_POSITIVE"
        val ATTACHED = Regex("^(?:Group|Field|Rule|Computation) with path '([^']*)': ")
        val BASE: String = DocumentModelDraft.named("policy-coverages").locale("en_US")
            .rootGroup("Policy") { policy ->
                policy.group("Coverages") { coverages ->
                    coverages.repeatable(4).stringField("Amount") { }
                        .group("Riders") { riders -> riders.repeatable(3).stringField("Code") { } }
                        .stringField("Note") { }
                }.stringField("Holder") { }
            }
            .build().toJson()
            .let { RuleValidator().inject(it, DraftRule("/Policy/Coverages", "AmountSet", "../Amount", "FieldFilled(Amount)", "AMOUNT_SET", "ERROR")) }
            .let { RuleValidator().inject(it, DraftComputation("/Policy/Coverages", "NoteFromAmount", "../Note", null,
                listOf(DraftComputation.Alternative(null, "[Amount]")))) }
        val ROWS = listOf(0, -1, -7).flatMap { count ->
            val node = mapper.valueToTree<com.fasterxml.jackson.databind.JsonNode>(count)
            listOf(Row(coverages = node), Row(riders = node), Row(root = node))
        } + listOf(
            Row(coverages = mapper.nullNode()), Row(riders = mapper.nullNode()),
            Row(coverages = mapper.valueToTree(0), riders = mapper.valueToTree(0)),
            Row(),
        )
    }
}
