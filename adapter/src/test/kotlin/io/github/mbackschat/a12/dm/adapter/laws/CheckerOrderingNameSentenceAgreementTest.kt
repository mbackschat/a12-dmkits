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
 * Whether a group's `indexFieldName` and `sortFields[].name` draw A12's field-name sentence of their own (KERNEL-FINDINGS
 * KF589), against the portable checker, every class with its multiplicity and the path it attaches to.
 *
 * The variables, each held while the others move: the name the member spells, whether the group's field carries that
 * name, where the spelling lands (the group's own field, a field outside it), the landed field's type, and whether an
 * element elsewhere is refused. A field with the name and no member is the baseline for the field's own sentence, so a
 * member's sentence shows as one more. Own domain (an order's line items), built by editing the drafted model's JSON,
 * because the typed builder refuses these names.
 */
class CheckerOrderingNameSentenceAgreementTest {

    @Test
    fun theCheckerStatesA12sSentencesForEachOrderingMember() {
        val observed = ROWS.associateWith { row -> model(row).let { kernel(it) to ours(it) } }
        assertEquals(observed.mapValues { it.value.first to it.value.first }, observed, "each row as (A12, ours)")
    }

    /** A12's own answer on the rows that separate the readings, so an agreement on a broken fixture cannot pass. */
    @Test
    fun a12sAnswerOnTheDiscriminatingRows() {
        val answer = { row: Row -> kernel(model(row)) }
        assertEquals(emptyList<String>(), answer(Row("Sku", index = "../Items/Sku")))
        assertEquals(listOf("$PATH@/Order/Items/Sk-u", "$SENTENCE@/Order/Items", "$SENTENCE@/Order/Items/Sk-u"),
            answer(Row("Sk-u", index = "Sk-u")))
        assertEquals(listOf("$INDEX_OUTSIDE@/Order/Items"), answer(Row("Sku", index = "../Channel")))
    }

    private data class Row(
        val field: String,
        val index: String? = null,
        val sorts: List<String> = emptyList(),
        val orderField: String = "Channel",
        val note: String = "Note",
        val noValueValidation: Boolean = false,
        val deleted: Boolean = false,
        val archiveRepeatability: Int? = null,
    )

    private fun base(noValueValidation: Boolean): String = DocumentModelDraft.named("order-line-ordering").locale("en_US")
        .rootGroup("Order") { order ->
            order.group("Items") { items ->
                items.stringField("Sku") { if (noValueValidation) it.noValueValidation().lineBreaks() }
                    .stringField("Note") { }.stringField("Qty") { }.stringField("Lot") { }
            }.stringField("Channel") { }.group("Archive") { a -> a.stringField("Text") { } }
        }
        .build().toJson()

    private fun model(row: Row): String {
        val tree = mapper.readTree(base(row.noValueValidation))
        val order = tree.at("/content/modelRoot/rootGroups/0/Group/elements") as ArrayNode
        (order[1] as ObjectNode).put("name", row.orderField).put("id", "/Order/${row.orderField}")
        row.archiveRepeatability?.let { (order[2].path("Group") as ObjectNode).put("repeatability", it) }
        val items = order[0].path("Group") as ObjectNode
        items.put("repeatability", 5)
        row.index?.let { items.put("indexFieldName", it) }
        if (row.sorts.isNotEmpty()) {
            val sorts = items.putArray("sortFields")
            row.sorts.forEach { sorts.addObject().put("name", it).put("order", "ASC") }
        }
        val fields = items.path("elements") as ArrayNode
        val sku = (fields[0] as ObjectNode).put("name", row.field).put("id", "/Order/Items/${row.field}")
        if (row.deleted) {
            (sku.path("Field") as ObjectNode).putObject("variantFilter").put("filterType", "ONLY").putArray("variants").add("Broker")
        }
        (fields[1] as ObjectNode).put("name", row.note).put("id", "/Order/Items/${row.note}")
        return mapper.writeValueAsString(tree)
    }

    private fun kernel(json: String): List<String> = try {
        oracle.checkConsistency(json, ReferenceResolvers.selfContained())
    } catch (unbuildable: ConsistencyCheckFailedException) {
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
    private val mapper = ObjectMapper()

    private companion object {
        const val PATH = "MVK_PATH_INVALID"
        const val SENTENCE = "RK_FIELD_NAME_ALPHABET"
        const val INDEX_OUTSIDE = "MVK_INVALID_INDEX_FIELD"
        val ATTACHED = Regex("^(?:Group|Field|Rule|Computation) with path '([^']*)': ")
        val NBSP_NAME = "Sk" + Char(0x00A0) + "u"
        val BAD = listOf(NBSP_NAME, "Sk-u", "Sku.1")
        // Names the field pattern admits beside ones it refuses, and a 61-character name (KF580's characters).
        val MORE = listOf("Ské", "S".repeat(61), "1Sku", "Ns:Sku", "Sku.a", "Sku.value")
        val ROWS = listOf(Row("Sku", index = "Sku"), Row("Sku", sorts = listOf("Sku"))) +
            listOf("../Items/Sku", " Sku", "Sku ").flatMap { spelled ->
                listOf(Row("Sku", index = spelled), Row("Sku", sorts = listOf(spelled)))
            } + listOf(
                Row(NBSP_NAME, index = "../Items/$NBSP_NAME"), Row(NBSP_NAME, sorts = listOf("../Items/$NBSP_NAME")),
                Row("Sku", index = "../$NBSP_NAME", orderField = NBSP_NAME),
                Row("Sku", sorts = listOf("../$NBSP_NAME"), orderField = NBSP_NAME),
                Row("Sku", index = "Sku", noValueValidation = true),
                Row(NBSP_NAME, index = NBSP_NAME, noValueValidation = true),
                Row(NBSP_NAME, sorts = listOf(NBSP_NAME), noValueValidation = true),
                // A refused element elsewhere, with every ordering gate otherwise reachable.
                Row("1Sku", index = "1Sku", noValueValidation = true),
                Row("Sku", index = "Sku", noValueValidation = true, note = "No-te"),
                Row("Sku", index = "../Channel"), Row("Sku", index = "../Channel", note = "No-te"),
                Row("Sku", sorts = listOf("../Channel"), note = "No-te"),
                // A refused element of another class A12 checks before the ordering members.
                Row("Sku", index = "Sku", noValueValidation = true, archiveRepeatability = 0),
                Row("Sku", index = "../Channel", archiveRepeatability = 0),
                // The duplicate verdict beside a refused element, and its control.
                Row("Sku", sorts = listOf("Sku", "Sku")), Row("Sku", sorts = listOf("Sku", "Sku"), note = "No-te"),
                // Whether a refused name withholds the duplicate verdict when the repeated name is a sound one.
                Row("Sku", sorts = listOf("Sku", "Sku", "Sk-u"), note = "Sk-u"),
                Row("Sku", sorts = listOf("Sku", "Sku", "Qty", "Sk-u"), note = "Sk-u"),
                // Multiplicity: a repeated spelling, and a bad name beyond the three entries A12 holds.
                Row("Sk-u", sorts = listOf("Sk-u", "Sk-u")),
                Row("Sk-u", sorts = listOf("Note", "Qty", "Lot", "Sk-u")),
                // The landed field deleted at the default variant.
                Row("Sk-u", index = "Sk-u", deleted = true), Row("Sk-u", sorts = listOf("Sk-u"), deleted = true),
            ) + (BAD + MORE).flatMap { bad ->
                listOf(
                    Row(bad), Row(bad, index = bad), Row("Sku", index = bad),
                    Row(bad, sorts = listOf(bad)), Row("Sku", sorts = listOf(bad)),
                )
            }
    }
}
