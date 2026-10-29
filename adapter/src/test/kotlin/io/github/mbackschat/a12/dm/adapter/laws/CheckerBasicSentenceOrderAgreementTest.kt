package io.github.mbackschat.a12.dm.adapter.laws

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import io.github.mbackschat.a12.dm.consistency.ModelSource
import io.github.mbackschat.a12.dm.consistency.check
import io.github.mbackschat.a12.dm.core.ModelConsistencyException
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * The checker against A12: the ORDER and number of A12's basic sentences, where the set-valued agreement of
 * `CheckerBasicSentenceAgreementTest` cannot see a difference (KERNEL-FINDINGS KF647).
 *
 * `BasicConsistencyCheck` walks the type definitions before the elements, each definition's empty id and then its
 * empty name, so three defective definitions draw three sentences ahead of an element's. It walks them as the
 * expansion left them, sorted by id: declared `Beta`, `''`, `Gamma`, they are stated `''`, `Beta`, `Gamma`, where
 * declaration order and `HashMap` order (`''`, `Gamma`, `Beta`) both disagree. Two definitions sharing an id, the
 * empty one included, never reach that check: A12's expansion refuses the repeat and builds no model. An
 * Enumeration's repeated values are collected by `Java8Utils.findDuplicates` into a `HashMap`, so they are
 * stated in that map's iteration order. The three values here separate that order from first-occurrence and
 * second-occurrence order alike: declared `OPEN, CLOSED, PENDING` and then reversed, they draw `CLOSED`,
 * `PENDING`, `OPEN` if the map decides, `OPEN, CLOSED, PENDING` if declaration does, and `PENDING, CLOSED, OPEN`
 * if the second occurrence does.
 *
 * Both columns are ordered lists: A12's sentences as `ModelConsistencyException` carries them, and the checker's
 * findings as `check` returns them, each restricted to the basic sentences.
 *
 * Own domain (an insurance policy with a status and a reference).
 */
class CheckerBasicSentenceOrderAgreementTest {

    private val mapper = ObjectMapper()

    private val base = DocumentModelDraft.named("policy-basic-order").locale("en_US")
        .rootGroup("Policy") { p -> p.stringField("Ref") { }.stringField("Code") { }
            .enumField("Status") { it.value("OPEN").value("CLOSED").value("PENDING") } }
        .build().toJson()

    @Test
    fun typeDefinitionSentencesComeFirstOnePerDefect() {
        val root = mapper.readTree(base) as ObjectNode
        val definitions = (root.path("content") as ObjectNode).putArray("typeDefinitions")
        for ((id, name) in listOf("Beta" to "", "" to "Alpha", "Gamma" to "")) definition(definitions, id, name)
        // A kept field's empty name fails the build first, so the field is deleted by a filter and keeps only its sentence.
        elements(root).single { it.path("name").asText() == "Code" }.let { code ->
            (code as ObjectNode).put("name", "")
            (code.path("Field") as ObjectNode).putObject("variantFilter").put("filterType", "ONLY").putArray("variants").add("premium")
        }
        val json = root.toString()
        assertEquals(TYPE_DEFINITION_ORDER, a12Basic(json), "A12's sentences, in order")
        assertEquals(TYPE_DEFINITION_ORDER, ourBasic(json), "the checker's findings, in order")
        // The classes alone cannot tell `Beta` from `Gamma`, so `HashMap` order would pass the two lines above.
        assertEquals(DEFINITION_IDS, a12DefinitionIds(json), "the definitions A12's sentences name, in order")
        assertEquals(DEFINITION_IDS, ourDefinitionIds(json), "the definitions the checker's findings name, in order")
    }

    @Test
    fun twoDefinitionsSharingAnIdLeaveNoModel() {
        for (id in listOf("", "Beta")) {
            val root = mapper.readTree(base) as ObjectNode
            val definitions = (root.path("content") as ObjectNode).putArray("typeDefinitions")
            for (name in listOf("Alpha", "Delta")) definition(definitions, id, name)
            val json = root.toString()
            val escaped = assertThrows<IllegalArgumentException> { BoundModel.ofJson(json) }
            assertEquals("The type definition id '$id' already exists.", escaped.cause?.message ?: escaped.message)
            assertTrue("CK_MODEL_NOT_BUILDABLE" in check(ModelSource(json)).findings.map { it.code }, "the checker says so for '$id'")
        }
    }

    @Test
    fun repeatedValuesComeInTheOrderA12sMapIterates() {
        val root = mapper.readTree(base) as ObjectNode
        val values = elements(root).single { it.path("name").asText() == "Status" }
            .path("Field").path("fieldType").path("EnumerationType").path("values") as ArrayNode
        values.toList().reversed().forEach { values.add(it.deepCopy<ObjectNode>()) }
        val json = root.toString()
        assertEquals(REPEATED_ORDER, a12Repeated(json), "A12's repeated values, in order")
        assertEquals(REPEATED_ORDER, ourRepeated(json), "the checker's repeated values, in order")
    }

    private fun elements(root: ObjectNode): ArrayNode =
        root.path("content").path("modelRoot").path("rootGroups").get(0).path("Group").path("elements") as ArrayNode

    private fun definition(into: ArrayNode, id: String, name: String) {
        into.addObject().put("id", id).put("name", name).put("revision", "1").putObject("fieldType")
            .put("type", "StringType").putObject("StringType")
    }

    private fun a12Sentences(json: String): List<String> =
        assertThrows<ModelConsistencyException> { BoundModel.ofJson(json) }.notifications().map { it.message().trim() }

    private fun a12Basic(json: String): List<String> = a12Sentences(json).map(::codelessClass).filter { it in BASIC }

    private fun ourBasic(json: String): List<String> = check(ModelSource(json)).findings.map { it.code }.filter { it in BASIC }

    /** Each definition sentence's id: A12 names a definition by its id, or by its name when the id is the empty defect. */
    private fun a12DefinitionIds(json: String): List<String> = a12Sentences(json).mapNotNull { sentence ->
        Regex("""^Type definition with (id|name) '([^']*)':""").find(sentence)?.groupValues
            ?.let { (_, by, value) -> if (by == "id") value else "" }
    }

    /** Each definition finding's id; the definitions' findings are the basic sentences with no element path. */
    private fun ourDefinitionIds(json: String): List<String> = check(ModelSource(json)).findings
        .filter { it.code in BASIC && it.at == null }
        .map { Regex("entry `([^`]*)`").find(it.explain)?.groupValues?.get(1) ?: "" }

    private fun a12Repeated(json: String): List<String> =
        a12Sentences(json).mapNotNull { Regex("""the value '([^']*)' multiple times\.$""").find(it)?.groupValues?.get(1) }

    private fun ourRepeated(json: String): List<String> = check(ModelSource(json)).findings
        .filter { it.code == "RK_ENUM_VALUE_DUPLICATE" }
        .mapNotNull { Regex("the value `([^`]*)`").find(it.explain)?.groupValues?.get(1) }

    private companion object {
        val BASIC = setOf("RK_ID_EMPTY", "RK_NAME_EMPTY")

        /** Measured through `BoundModel`, kernel 30.8.1, 2026-10-29. */
        val TYPE_DEFINITION_ORDER = listOf("RK_ID_EMPTY", "RK_NAME_EMPTY", "RK_NAME_EMPTY", "RK_NAME_EMPTY")

        /** Measured through `BoundModel`, kernel 30.8.1, 2026-10-29: `Type definition with name 'Alpha': The id is empty.`, then `Beta`'s and `Gamma`'s name sentences. */
        val DEFINITION_IDS = listOf("", "Beta", "Gamma")

        /** Measured through `BoundModel`, kernel 30.8.1, 2026-10-29. */
        val REPEATED_ORDER = listOf("CLOSED", "PENDING", "OPEN")
    }
}
