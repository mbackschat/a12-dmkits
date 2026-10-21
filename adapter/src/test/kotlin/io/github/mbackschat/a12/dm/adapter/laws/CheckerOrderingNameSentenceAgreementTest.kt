package io.github.mbackschat.a12.dm.adapter.laws

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import io.github.mbackschat.a12.dm.adapter.KernelConsistencyOracle
import io.github.mbackschat.a12.dm.consistency.ModelSource
import io.github.mbackschat.a12.dm.consistency.check
import io.github.mbackschat.a12.dm.core.ConsistencyCheckFailedException
import io.github.mbackschat.a12.dm.core.ReferenceResolvers
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Whether a group's `indexFieldName` and `sortFields[].name` draw A12's field-name sentence of their own, against
 * the portable checker, every class with its multiplicity.
 *
 * Two variables, each held while the other moves: the name the member spells, and whether the group's field
 * carries that same name. A field with the name and no member is the baseline for the field's own sentence, so a
 * member's sentence shows as one more. Own domain (an order's line items), built by editing the drafted model's
 * JSON, because the typed builder refuses these names.
 */
class CheckerOrderingNameSentenceAgreementTest {

    @Test
    fun theCheckerStatesA12sSentencesForEachOrderingMember() {
        val observed = ROWS.associateWith { row -> model(row).let { kernel(it) to ours(it) } }
        assertEquals(observed.mapValues { it.value.first to it.value.first }, observed, "each row as (A12, ours)")
    }

    private enum class Member { NONE, INDEX, SORT }

    private data class Row(val field: String, val member: Member, val value: String, val orderField: String? = null,
        val noValueValidation: Boolean = false)

    private fun base(noValueValidation: Boolean): String = DocumentModelDraft.named("order-line-ordering").locale("en_US")
        .rootGroup("Order") { order ->
            order.group("Items") { items ->
                items.stringField("Sku") { if (noValueValidation) it.noValueValidation().lineBreaks() }.stringField("Note") { }
            }.stringField("Channel") { }
        }
        .build().toJson()

    private fun model(row: Row): String {
        val (field, member, value, orderField) = row
        val tree = mapper.readTree(base(row.noValueValidation))
        if (orderField != null) {
            (tree.at("/content/modelRoot/rootGroups/0/Group/elements/1") as ObjectNode).put("name", orderField).put("id", "/Order/$orderField")
        }
        val items = tree.at("/content/modelRoot/rootGroups/0/Group/elements/0/Group") as ObjectNode
        items.put("repeatability", 5)
        when (member) {
            Member.NONE -> Unit
            Member.INDEX -> items.put("indexFieldName", value)
            Member.SORT -> items.putArray("sortFields").addObject().put("name", value).put("order", "ASC")
        }
        ((items.path("elements") as ArrayNode)[0] as ObjectNode).put("name", field).put("id", "/Order/Items/$field")
        return mapper.writeValueAsString(tree)
    }

    private fun kernel(json: String): List<String> = try {
        oracle.checkConsistency(json, ReferenceResolvers.selfContained())
    } catch (unbuildable: ConsistencyCheckFailedException) {
        return listOf("CK_MODEL_NOT_BUILDABLE")
    }.map { notification ->
        val message = notification.message().trim()
        Regex("\\[(\\w+)]$").find(message)?.groupValues?.get(1)
            ?: codelessClass(message).let { if (it == UNKEYED_A12_CLASS) "codeless:$message" else it }
    }.sorted()

    private fun ours(json: String): List<String> =
        check(ModelSource(json)).findings.filter { it.justifiesRejection }.map { it.code }.sorted()

    private val oracle = KernelConsistencyOracle()
    private val mapper = ObjectMapper()

    private companion object {
        val NBSP_NAME = "Sk" + Char(0x00A0) + "u"
        val BAD = listOf(NBSP_NAME, "Sk-u", "Sku.1")
        val ROWS = listOf(Row("Sku", Member.INDEX, "Sku"), Row("Sku", Member.SORT, "Sku")) +
            listOf("../Items/Sku", " Sku", "Sku ").flatMap { spelled ->
                listOf(Row("Sku", Member.INDEX, spelled), Row("Sku", Member.SORT, spelled))
            } + listOf(
                Row(NBSP_NAME, Member.INDEX, "../Items/$NBSP_NAME"), Row(NBSP_NAME, Member.SORT, "../Items/$NBSP_NAME"),
                Row("Sku", Member.INDEX, "../$NBSP_NAME", orderField = NBSP_NAME),
                Row("Sku", Member.SORT, "../$NBSP_NAME", orderField = NBSP_NAME),
                Row("Sku", Member.INDEX, "Sku", noValueValidation = true),
                Row(NBSP_NAME, Member.INDEX, NBSP_NAME, noValueValidation = true),
                Row(NBSP_NAME, Member.SORT, NBSP_NAME, noValueValidation = true),
            ) + BAD.flatMap { bad ->
            listOf(
                Row(bad, Member.NONE, ""),
                Row(bad, Member.INDEX, bad), Row("Sku", Member.INDEX, bad),
                Row(bad, Member.SORT, bad), Row("Sku", Member.SORT, bad),
            )
        }
    }
}
