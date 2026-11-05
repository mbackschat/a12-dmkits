package io.github.mbackschat.a12.dm.adapter.laws

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import io.github.mbackschat.a12.dm.adapter.KernelConsistencyOracle
import io.github.mbackschat.a12.dm.consistency.ConsistencyWorkspace
import io.github.mbackschat.a12.dm.consistency.ModelSource
import io.github.mbackschat.a12.dm.consistency.check
import io.github.mbackschat.a12.dm.core.ReferenceResolvers
import io.github.mbackschat.a12.dm.core.ModelConsistencyException
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * The checker against A12: the ORDER and number of A12's basic sentences, where the set-valued agreement of
 * `CheckerBasicSentenceAgreementTest` cannot see a difference (KERNEL-FINDINGS KF647).
 *
 * `BasicConsistencyCheck` walks the type definitions before the elements, each definition's empty id and then its
 * empty name, so three defective definitions draw three sentences ahead of an element's. It walks them as the
 * expansion left them. On kernel 31.1.1 that is declaration order for a model with nothing to expand, and order by
 * id only for one A12 expands: declared `Beta`, `''`, `Gamma`, they are stated as declared, and `''`, `Beta`,
 * `Gamma` once the model imports a type-definition library, where `HashMap` order (`''`, `Gamma`, `Beta`) disagrees
 * with both. Two definitions sharing an id, the empty one included, never reach that check: the model reads, but A12
 * cannot build the one its consistency check runs on. An
 * Enumeration's repeated values are collected by `Java8Utils.findDuplicates` into a `HashMap` through
 * `computeIfAbsent`, so they are stated in that map's iteration order. The three values of `Status` separate that
 * order from first-occurrence and second-occurrence order alike: declared `OPEN, CLOSED, PENDING` and then
 * reversed, they draw `CLOSED`, `PENDING`, `OPEN` if the map decides, `OPEN, CLOSED, PENDING` if declaration does,
 * and `PENDING, CLOSED, OPEN` if the second occurrence does. `Frequency` and `Line` separate `computeIfAbsent`'s
 * fill from `put`'s: `QUARTERLY` and `ONCE` share a bucket, where the newer key goes first, and `Line`'s thirteen
 * values cross the resize threshold, which `computeIfAbsent` acts on only at its next call. `Plan`'s eight values
 * share one of the sixteen buckets, so its eighth key resizes the table where a ninth key would treeify a larger
 * one; the bucket's keys split by their fifth hash bit, which a map that did not resize would leave interleaved.
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
            .enumField("Status") { it.value("OPEN").value("CLOSED").value("PENDING") }
            .enumField("Frequency") { e -> FREQUENCIES.forEach { e.value(it) } }
            .enumField("Line") { e -> LINES.forEach { e.value(it) } }
            .enumField("Plan") { e -> PLANS.forEach { e.value(it) } } }
        .build().toJson()

    @Test
    fun typeDefinitionSentencesComeFirstOnePerDefect() {
        val root = mapper.readTree(base) as ObjectNode
        val definitions = (root.path("content") as ObjectNode).putArray("typeDefinitions")
        for ((id, name) in listOf("Beta" to "", "" to "Alpha", "Gamma" to "")) definition(definitions, id, name)
        (elements(root).single { it.path("name").asText() == "Code" } as ObjectNode).put("name", "")
        val json = root.toString()
        assertEquals(TYPE_DEFINITION_ORDER, a12Basic(json), "A12's sentences, in order")
        assertEquals(TYPE_DEFINITION_ORDER, ourBasic(json), "the checker's findings, in order")
        // The classes alone cannot tell `Beta` from `Gamma`, so `HashMap` order would pass the two lines above.
        assertEquals(DEFINITION_IDS, a12DefinitionIds(json), "the definitions A12's sentences name, in order")
        assertEquals(DEFINITION_IDS, ourDefinitionIds(json), "the definitions the checker's findings name, in order")
    }

    @Test
    fun aModelA12ExpandsStatesItsDefinitionsSortedById(@TempDir dir: Path) {
        val ids = importing(dir, listOf("Gamma" to "", "Beta" to "", "Delta" to ""))
        assertEquals(SORTED_DEFINITION_IDS to SORTED_DEFINITION_IDS, ids, "the definitions named in order (A12's, the checker's)")
    }

    /**
     * An empty id reaches A12's expansion as `null` (KF658), and adding the library's definition compares every id
     * the model already holds, so the expansion fails and no model is left — where the same definition in a model
     * with nothing to expand is one `RK_ID_EMPTY` sentence.
     */
    @Test
    fun anEmptyIdLeavesAModelA12ExpandsUnbuilt(@TempDir dir: Path) {
        Files.writeString(dir.resolve("library.dm.json"), LIBRARY)
        val json = importingModel(listOf("" to "Alpha"))
        val a12 = runCatching { KernelConsistencyOracle().checkConsistency(json, ReferenceResolvers.directory(dir)) }
        assertTrue(a12.exceptionOrNull()?.message.orEmpty().contains("could not build"), "A12: $a12")
        assertTrue("CK_MODEL_NOT_BUILDABLE" in ConsistencyWorkspace.fromSources(listOf(ModelSource(json), ModelSource(LIBRARY)))
            .check("policy-basic-order").findings.map { it.code }, "the checker says so")
    }

    @Test
    fun typeDefinitionIdsCompareByCodeUnitNotByCase(@TempDir dir: Path) {
        val ids = importing(dir, listOf("deductible", "Prämie", "Coverage", "Überschuss").map { it to "" })
        assertEquals(CODE_UNIT_IDS to CODE_UNIT_IDS, ids, "the definitions named in order (A12's, the checker's)")
    }

    @Test
    fun aSelfContainedModelStatesItsDefinitionsAsDeclared() {
        val root = mapper.readTree(base) as ObjectNode
        val definitions = (root.path("content") as ObjectNode).putArray("typeDefinitions")
        for (id in DECLARED_IDS) definition(definitions, id, "")
        val json = root.toString()
        assertEquals(DECLARED_IDS, a12DefinitionIds(json), "the definitions A12's sentences name, in order")
        assertEquals(DECLARED_IDS, ourDefinitionIds(json), "the definitions the checker's findings name, in order")
    }

    /** The base with [definitions], importing [LIBRARY]. */
    private fun importingModel(definitions: List<Pair<String, String>>): String {
        val root = mapper.readTree(base) as ObjectNode
        (root.path("header") as ObjectNode).putArray("modelReferences").addObject()
            .put("modelType", "document").put("purpose", "typeDefinitions").put("reference", "library")
        val declared = (root.path("content") as ObjectNode).putArray("typeDefinitions")
        for ((id, name) in definitions) definition(declared, id, name)
        return root.toString()
    }

    /** The definitions each side names, in order, for the base with [definitions] that imports [LIBRARY]. */
    private fun importing(dir: Path, definitions: List<Pair<String, String>>): Pair<List<String>, List<String>> {
        Files.writeString(dir.resolve("library.dm.json"), LIBRARY)
        val json = importingModel(definitions)
        val a12 = KernelConsistencyOracle().checkConsistency(json, ReferenceResolvers.directory(dir)).map { it.message().trim() }
        val ours = ConsistencyWorkspace.fromSources(listOf(ModelSource(json), ModelSource(LIBRARY))).check("policy-basic-order")
            .findings.filter { it.code in BASIC && it.at == null }
            .map { Regex("entry `([^`]*)`").find(it.explain)?.groupValues?.get(1) ?: "" }
        return a12.mapNotNull(::definitionId) to ours
    }

    @Test
    fun twoDefinitionsSharingAnIdLeaveNoModel() {
        for (id in listOf("", "Beta")) {
            val root = mapper.readTree(base) as ObjectNode
            val definitions = (root.path("content") as ObjectNode).putArray("typeDefinitions")
            for (name in listOf("Alpha", "Delta")) definition(definitions, id, name)
            val json = root.toString()
            val refused = assertThrows<ModelConsistencyException> { BoundModel.ofJson(json) }
            // The empty id reaches the build as `null`, the expand copy's empty-as-absent reading (KF658).
            val key = id.ifEmpty { "null" }
            assertTrue("Duplicate key $key " in refused.message.orEmpty(), "A12's consistency build trips on '$id': ${refused.message}")
            assertTrue("CK_MODEL_NOT_BUILDABLE" in check(ModelSource(json)).findings.map { it.code }, "the checker says so for '$id'")
        }
    }

    @Test
    fun repeatedValuesComeInTheOrderA12sMapIterates() {
        for ((field, expected) in listOf("Status" to REPEATED_ORDER, "Frequency" to FREQUENCY_ORDER, "Line" to LINE_ORDER)) {
            val json = repeating(field) { values -> values.toList().reversed().forEach { values.add(it.deepCopy<ObjectNode>()) } }
            assertEquals(expected, a12Repeated(json), "A12's repeated values of $field, in order")
            assertEquals(expected, ourRepeated(json), "the checker's repeated values of $field, in order")
        }
    }

    @Test
    fun aThirdOccurrenceStillCallsTheMapAndCanResizeIt() {
        val json = repeating("Line") { values ->
            values.toList().reversed().forEach { values.add(it.deepCopy<ObjectNode>()) }
            values.add(values.first().deepCopy<ObjectNode>())
        }
        assertEquals(LINE_THIRD_ORDER, a12Repeated(json), "A12's repeated values, in order")
        assertEquals(LINE_THIRD_ORDER, ourRepeated(json), "the checker's repeated values, in order")
    }

    /** Seven keys in one bucket leave the table at 16; the eighth doubles it (a12-kernel-lean SPEC-2026-11-05-05). */
    @Test
    fun anEighthKeyInOneBucketResizesTheMap() {
        for ((count, expected) in listOf(7 to PLAN_SEVEN_ORDER, 8 to PLAN_EIGHT_ORDER)) {
            val json = repeating("Plan") { values -> values.toList().take(count).forEach { values.add(it.deepCopy<ObjectNode>()) } }
            assertEquals(expected, a12Repeated(json), "A12's repeated values of $count plans, in order")
            assertEquals(expected, ourRepeated(json), "the checker's repeated values of $count plans, in order")
        }
    }

    private fun repeating(field: String, edit: (ArrayNode) -> Unit): String {
        val root = mapper.readTree(base) as ObjectNode
        edit(elements(root).single { it.path("name").asText() == field }
            .path("Field").path("fieldType").path("EnumerationType").path("values") as ArrayNode)
        return root.toString()
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
    private fun a12DefinitionIds(json: String): List<String> = a12Sentences(json).mapNotNull(::definitionId)

    private fun definitionId(sentence: String): String? =
        Regex("""^Type definition with (id|name) '([^']*)':""").find(sentence)?.groupValues
            ?.let { (_, by, value) -> if (by == "id") value else "" }

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

        /** Measured through `BoundModel`, kernel 31.1.1, 2026-11-03: `Beta`'s name, `Alpha`'s id, `Gamma`'s name, then `Code`'s. */
        val TYPE_DEFINITION_ORDER = listOf("RK_NAME_EMPTY", "RK_ID_EMPTY", "RK_NAME_EMPTY", "RK_NAME_EMPTY")

        /** Measured through `BoundModel`, kernel 31.1.1, 2026-11-03: declaration order. */
        val DEFINITION_IDS = listOf("Beta", "", "Gamma")

        /** Declared `Gamma`, `Beta`, `Delta` in a model that imports a library: sorted by id. */
        val SORTED_DEFINITION_IDS = listOf("Beta", "Delta", "Gamma")

        /** Sorted by UTF-16 code unit, as `String.compareTo` orders them: capitals, then lower case, then `Ü`. */
        val CODE_UNIT_IDS = listOf("Coverage", "Prämie", "deductible", "Überschuss")

        /** Not sorted, so this order tells declaration order from order by id; measured through `BoundModel`, kernel 31.1.1, 2026-11-03. */
        val DECLARED_IDS = listOf("deductible", "Prämie", "Coverage", "Überschuss")

        /** A type-definition library with one well-formed definition, which makes A12 expand its importer. */
        val LIBRARY: String = DocumentModelDraft.typeDefModel("library").locale("en_US")
            .typeDef("Region") { t -> t.string { s -> s.maxLength(8) } }.build().toJson()

        /** Measured through `BoundModel`, kernel 30.8.1, 2026-10-29. */
        val REPEATED_ORDER = listOf("CLOSED", "PENDING", "OPEN")

        val FREQUENCIES = listOf("MONTHLY", "QUARTERLY", "ANNUAL", "WEEKLY", "ONCE")

        val LINES = listOf("LIFE", "HOME", "AUTO", "TRAVEL", "HEALTH", "PET", "BOAT", "FARM", "CYBER", "LEGAL", "DENTAL", "FLOOD", "THEFT")

        /** Measured through `BoundModel`, kernel 30.8.1, 2026-10-29. */
        val FREQUENCY_ORDER = listOf("WEEKLY", "QUARTERLY", "ONCE", "ANNUAL", "MONTHLY")

        /** Measured through `BoundModel`, kernel 30.8.1, 2026-10-29. */
        val LINE_ORDER = listOf("CYBER", "FLOOD", "AUTO", "TRAVEL", "FARM", "LEGAL", "DENTAL", "HEALTH", "HOME", "PET", "THEFT", "LIFE", "BOAT")

        /** Every one in bucket 14 of 16; `R1`, `S2`, `T3` and `U4` have the fifth spread hash bit clear, the others set. */
        val PLANS = listOf("PLAN_R1", "PLAN_A0", "PLAN_S2", "PLAN_B1", "PLAN_T3", "PLAN_C2", "PLAN_U4", "PLAN_D3")

        /** Measured through `BoundModel`, kernel 31.1.1, 2026-11-05: newest first, one bucket. */
        val PLAN_SEVEN_ORDER = listOf("PLAN_U4", "PLAN_C2", "PLAN_T3", "PLAN_B1", "PLAN_S2", "PLAN_A0", "PLAN_R1")

        /** Measured through `BoundModel`, kernel 31.1.1, 2026-11-05: split into bucket 14 and then 30. Without the
         *  resize the bucket would read `D3`, `U4`, `C2`, `T3`, `B1`, `S2`, `A0`, `R1`. */
        val PLAN_EIGHT_ORDER = listOf("PLAN_U4", "PLAN_T3", "PLAN_S2", "PLAN_R1", "PLAN_D3", "PLAN_C2", "PLAN_B1", "PLAN_A0")

        /** Measured through `BoundModel`, kernel 30.8.1, 2026-10-29. */
        val LINE_THIRD_ORDER = listOf("CYBER", "FLOOD", "TRAVEL", "DENTAL", "AUTO", "FARM", "LEGAL", "HEALTH", "HOME", "PET", "THEFT", "LIFE", "BOAT")
    }
}
