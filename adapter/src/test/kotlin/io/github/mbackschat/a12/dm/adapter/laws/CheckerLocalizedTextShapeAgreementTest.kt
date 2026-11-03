package io.github.mbackschat.a12.dm.adapter.laws

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode
import io.github.mbackschat.a12.dm.consistency.ModelSource
import io.github.mbackschat.a12.dm.consistency.check
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The checker against A12: a localized-text member whose shape A12's reader refuses leaves no model, on every member
 * that binds through `LocalizedTextMapDeserializer` (KERNEL-FINDINGS KF646).
 *
 * The deserializer loops over the member's entries and demands a `locale` and a `text`, each a JSON string, the
 * locale in a spelling its parser accepts, so a `null` text refuses the source, as does a number, a missing key or
 * an entry that is not an object. The member's own shape was the open question, since the descriptions bind
 * through `ElementDeserializer` and the rest through Jackson's property binding; it behaves alike on all eleven
 * carriers: a scalar, an empty list or an object of entries binds, and `null` refuses.
 *
 * A12's column is asserted, not copied: every row builds the model, and the set of shapes that leave none is
 * pinned. Elsewhere a row may draw a class of its own (all three values labelled alike repeat a display value; a
 * rule or string type without a usable message lacks one), and the checker must name exactly that class.
 *
 * Own domain (an insurance policy with a status, a reference and a list of insured items).
 */
class CheckerLocalizedTextShapeAgreementTest {

    private val mapper = ObjectMapper()

    private val base = DocumentModelDraft.named("policy-localized-text").locale("en_US")
        .rootGroup("Policy") { p -> p.stringField("Ref") { }
            .enumField("Status") { it.value("OPEN").value("CLOSED") }
            .group("Items") { g -> g.repeatable(3).stringField("Label") { } } }
        .build().toJson().let { RuntimeLaws.withRule(it, "/Policy", "RefCheck", "../Ref", "REF", "FieldFilled(Ref)") }

    @Test
    fun theBaseBuilds() {
        assertEquals(emptySet<String>(), kernelModelClasses(base))
    }

    @Test
    fun aLocalizedTextShapeTheReaderRefusesLeavesNoModel() {
        val actual = MEMBERS.flatMap { (member, locate) ->
            SHAPES.flatMap { (shape, value) ->
                val root = mapper.readTree(base) as ObjectNode
                locate(Elements(root), value(JsonNodeFactory.instance))
                val json = root.toString()
                listOf("$member · $shape" to (a12Verdict(json) to ourVerdict(json)))
            }
        }.toMap()
        assertEquals(actual.mapValues { (row, _) -> row.split(" · ")[1] in REFUSED },
            actual.mapValues { (_, v) -> v.first == UNBUILT }, "A12 builds no model on exactly the refused shapes")
        assertEquals(actual.mapValues { (_, v) -> v.first to v.first }, actual, "as (A12, ours)")
    }

    /** A12's classes, with an escape past its typed channels read as the model it never built. */
    private fun a12Verdict(json: String): String = try {
        verdictOf(kernelModelClasses(json).toSortedSet())
    } catch (escaped: RuntimeException) {
        UNBUILT
    }

    private fun ourVerdict(json: String): String {
        return verdictOf(check(ModelSource(json)).findings.filter { it.justifiesRejection }.mapTo(sortedSetOf()) { it.code })
    }

    private fun verdictOf(codes: Set<String>): String = when (codes) {
        emptySet<String>() -> BUILT
        setOf(CK) -> UNBUILT
        else -> codes.toString()
    }

    private class Elements(val root: ObjectNode) {
        fun element(name: String): ObjectNode =
            (root.path("content").path("modelRoot").path("rootGroups").get(0).path("Group").path("elements") as ArrayNode)
                .single { it.path("name").asText() == name } as ObjectNode

        fun field(name: String): ObjectNode = element(name).path("Field") as ObjectNode
        fun group(name: String): ObjectNode = element(name).path("Group") as ObjectNode
        fun enumBody(): ObjectNode = field("Status").path("fieldType").path("EnumerationType") as ObjectNode
        fun rule(): ObjectNode = element("RefCheck").path("Rule") as ObjectNode
    }

    private companion object {
        const val CK = "CK_MODEL_NOT_BUILDABLE"
        const val BUILT = "built"
        const val UNBUILT = "unbuilt"

        fun entry(f: JsonNodeFactory, locale: JsonNode?, text: JsonNode?): ObjectNode = f.objectNode().apply {
            locale?.let { set<JsonNode>("locale", it) }
            text?.let { set<JsonNode>("text", it) }
        }

        fun list(f: JsonNodeFactory, vararg entries: JsonNode): ArrayNode = f.arrayNode().addAll(entries.toList())

        /** Each shape of the member's value; the first is the control that a well-formed member builds. */
        val SHAPES: Map<String, (JsonNodeFactory) -> JsonNode> = linkedMapOf(
            "a well-formed entry" to { f -> list(f, entry(f, f.textNode("en_US"), f.textNode("x"))) },
            "an empty text" to { f -> list(f, entry(f, f.textNode("en_US"), f.textNode(""))) },
            "a null text" to { f -> list(f, entry(f, f.textNode("en_US"), f.nullNode())) },
            "a number for a text" to { f -> list(f, entry(f, f.textNode("en_US"), f.numberNode(5))) },
            "a boolean for a text" to { f -> list(f, entry(f, f.textNode("en_US"), f.booleanNode(true))) },
            "an array for a text" to { f -> list(f, entry(f, f.textNode("en_US"), f.arrayNode())) },
            "no text" to { f -> list(f, entry(f, f.textNode("en_US"), null)) },
            "a null locale" to { f -> list(f, entry(f, f.nullNode(), f.textNode("x"))) },
            "a number for a locale" to { f -> list(f, entry(f, f.numberNode(5), f.textNode("x"))) },
            "no locale" to { f -> list(f, entry(f, null, f.textNode("x"))) },
            "a refused locale spelling" to { f -> list(f, entry(f, f.textNode("klingon"), f.textNode("x"))) },
            "an entry that is text" to { f -> list(f, f.textNode("x")) },
            "an entry that is null" to { f -> list(f, f.nullNode()) },
            "an empty list" to { f -> f.arrayNode() },
            "an object of entries" to { f -> f.objectNode().set("a", entry(f, f.textNode("en_US"), f.textNode("x"))) },
            "an object holding a null text" to { f -> f.objectNode().set("a", entry(f, f.textNode("en_US"), f.nullNode())) },
            "text for the member" to { f -> f.textNode("x") },
            "a number for the member" to { f -> f.numberNode(5) },
            "null for the member" to { f -> f.nullNode() },
        )

        /** The shapes A12's reader refuses on every carrier, measured through `BoundModel`, kernel 30.8.1, 2026-10-29. */
        val REFUSED = setOf(
            "a null text", "a number for a text", "a boolean for a text", "an array for a text", "no text",
            "a null locale", "a number for a locale", "no locale", "a refused locale spelling", "an entry that is text",
            "an entry that is null", "an object holding a null text", "null for the member",
        )

        /** Every member bound through A12's text-map reader, each placed on its carrier. */
        val MEMBERS: Map<String, (Elements, JsonNode) -> Unit> = linkedMapOf(
            "a field's label" to { e, v -> e.field("Ref").set<JsonNode>("label", v) },
            "a field's helper text" to { e, v -> e.field("Ref").set<JsonNode>("helperText", v) },
            "a group's label" to { e, v -> e.group("Items").set<JsonNode>("label", v) },
            "an element's external description" to { e, v -> e.element("Ref").set<JsonNode>("externalDescription", v) },
            "an element's internal description" to { e, v -> e.element("Ref").set<JsonNode>("internalDescription", v) },
            "every enumeration value's label" to { e, v -> e.enumBody().path("values").forEach { (it as ObjectNode).set<JsonNode>("label", v.deepCopy()) } },
            "an enumeration type's error message" to { e, v -> e.enumBody().set<JsonNode>("errorMessage", v) },
            "a type definition's description" to { e, v ->
                (e.root.path("content") as ObjectNode).putArray("typeDefinitions").addObject().put("id", "RefType")
                    .put("name", "RefType").put("revision", "1").also { it.set<JsonNode>("internalDescription", v) }
                    .putObject("fieldType").put("type", "StringType").putObject("StringType")
            },
            "a rule's error message" to { e, v -> e.rule().set<JsonNode>("errorMessage", v) },
            "a string type's error message" to { e, v ->
                e.field("Ref").withObject("/fieldType/StringType").put("pattern", "[A-Z]+").set<JsonNode>("errorMessage", v)
            },
            "a requiredness configuration's error message" to { e, v ->
                e.field("Ref").withObject("/requirednessConfig").put("mode", "absoluteOrRelativeToNextRepAncestor").set<JsonNode>("errorMessage", v)
            },
        )
    }
}
