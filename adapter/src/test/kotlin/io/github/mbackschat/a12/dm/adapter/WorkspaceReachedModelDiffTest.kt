package io.github.mbackschat.a12.dm.adapter

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import io.github.mbackschat.a12.dm.consistency.CK_MODEL_NOT_BUILDABLE
import io.github.mbackschat.a12.dm.consistency.ConsistencyWorkspace
import io.github.mbackschat.a12.dm.consistency.ModelSource
import io.github.mbackschat.a12.dm.interpreter.ModelWorkspace
import io.github.mbackschat.a12.dm.interpreter.WorkspaceModelSource
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * What A12 reads from the models an entry model reaches, an included model and a type-definition library, and that
 * the interpreter's workspace and the portable checker refuse the same entries (KERNEL-FINDINGS KF594). Own domain
 * (an insurance cover, its component and its code types).
 */
class WorkspaceReachedModelDiffTest {

    /**
     * Every model the entry reaches is read whole, the entry itself, an included model and a type-definition library
     * alike, so a model-level member A12 cannot bind in any of them leaves the entry model unbuilt: the base year and
     * the three primitive `boolean`s. Each carrier's untouched and bound rows are the controls.
     */
    @Test
    fun aReachedModelsUnbindableModelMemberLeavesTheEntryUnbuilt() {
        val members = linkedMapOf(
            "modelInfo.baseYear \"NULL\"" to listOf(Triple("modelInfo", "baseYear", "NULL")),
            "modelInfo.immutable 1.5" to listOf(Triple("modelInfo", "immutable", 1.5)),
            "modelInfo.metaData 1.5" to listOf(Triple("modelInfo", "metaData", 1.5)),
            "modelConfig.fieldRefByShortNameAllowed 1.5" to listOf(Triple("modelConfig", "fieldRefByShortNameAllowed", 1.5)),
        )
        val bound = listOf(Triple("modelInfo", "baseYear", 2024), Triple("modelInfo", "immutable", false),
            Triple("modelInfo", "metaData", false), Triple("modelConfig", "fieldRefByShortNameAllowed", false))
        val observed = linkedMapOf<String, String>()
        for (carrier in Carrier.entries) {
            observed["$carrier untouched"] = observe(carrier) {}
            observed["$carrier bound"] = observe(carrier) { content -> bound.forEach { content.setMember(it) } }
            members.forEach { (row, edits) -> observed["$carrier $row"] = observe(carrier) { content -> edits.forEach { content.setMember(it) } } }
        }
        assertEquals(observed.mapValues { (row, _) ->
            if (row.endsWith("untouched") || row.endsWith("bound")) BUILT else NOT_BUILT
        }, observed)
    }

    /**
     * A type-definition library is expanded only when it declares no root group: one root group, empty or not, leaves
     * the entry unbuilt ("Cannot expand import because the root group of the imported model … contains elements").
     * The library without a root group is the control.
     */
    @Test
    fun aTypeDefinitionLibraryWithARootGroupLeavesTheEntryUnbuilt() {
        fun library(edit: (ObjectNode) -> Unit) = (MAPPER.readTree(TYPES) as ObjectNode).also { edit(it.path("content") as ObjectNode) }.toString()
        fun group(name: String, elements: String) =
            MAPPER.readTree("""{"type":"Group","id":"/$name","name":"$name","Group":{"elements":$elements}}""")
        val field = """[{"type":"Field","id":"/Types/Note","name":"Note","Field":{"fieldType":{"type":"StringType"}}}]"""
        val rows = linkedMapOf(
            "no root group" to library {},
            "no modelRoot" to library { it.remove("modelRoot") },
            "an empty root group" to library { (it.at("/modelRoot/rootGroups") as ArrayNode).add(group("Types", "[]")) },
            "a root group holding a field" to library { (it.at("/modelRoot/rootGroups") as ArrayNode).add(group("Types", field)) },
            "two empty root groups" to library {
                (it.at("/modelRoot/rootGroups") as ArrayNode).add(group("Types", "[]")).add(group("More", "[]"))
            },
        )
        val observed = rows.mapValues { (_, library) -> observe(coded().toString(), library) }
        assertEquals(rows.mapValues { (row, _) -> if (row.startsWith("no ")) BUILT else NOT_BUILT }, observed)
    }

    /**
     * A reached model must declare every locale the entry declares, an included model and a type-definition library
     * alike: the demand is coverage, so a superset is admitted (`DocumentModelExpandSanityChecks.checkLocaleCompatibility`).
     */
    @Test
    fun aReachedModelMissingAnEntryLocaleLeavesTheEntryUnbuilt() {
        val rows = linkedMapOf<String, String>()
        for (carrier in listOf(Carrier.INCLUDE, Carrier.TYPE_DEFINITIONS)) {
            for ((row, locales) in listOf("same locales" to listOf("en_US", "de_DE"), "a superset" to listOf("en_US", "de_DE", "fr_FR"),
                "missing de_DE" to listOf("en_US"))) {
                val (entry, provider) = when (carrier) {
                    Carrier.INCLUDE -> permit() to MAPPER.readTree(COMPONENT) as ObjectNode
                    else -> coded() to MAPPER.readTree(TYPES) as ObjectNode
                }
                entry.withLocales(listOf("en_US", "de_DE"))
                provider.withLocales(locales)
                rows["$carrier $row"] = observe(entry.toString(), provider.toString())
            }
        }
        assertEquals(rows.mapValues { (row, _) -> if (row.endsWith("missing de_DE")) NOT_BUILT else BUILT }, rows)
    }

    private fun ObjectNode.withLocales(codes: List<String>) {
        val locales = (path("header") as ObjectNode).putArray("locales")
        codes.forEach { locales.addObject().put("code", it) }
    }

    private enum class Carrier { ENTRY, INCLUDE, TYPE_DEFINITIONS }

    /**
     * A12's build, the interpreter's preparation and the checker's refusal of an entry model, with [edit] applied to
     * the `content` of the model [carrier] names.
     */
    private fun observe(carrier: Carrier, edit: (ObjectNode) -> Unit): String {
        val (entry, provider) = when (carrier) {
            Carrier.ENTRY -> cover() to null
            Carrier.INCLUDE -> permit() to MAPPER.readTree(COMPONENT) as ObjectNode
            Carrier.TYPE_DEFINITIONS -> coded() to MAPPER.readTree(TYPES) as ObjectNode
        }
        edit((provider ?: entry).path("content") as ObjectNode)
        return observe(entry.toString(), provider?.toString())
    }

    private fun observe(entry: String, provider: String?): String {
        val sources = listOfNotNull(entry, provider)
        val providerId = provider?.let { MAPPER.readTree(it).at("/header/id").asText() }
        val entryId = MAPPER.readTree(entry).at("/header/id").asText()
        val a12 = runCatching { KernelModelOracle.of(entry) { if (it == providerId) provider else null } }.isSuccess
        val interpreter = runCatching {
            ModelWorkspace.fromSources(sources.map { WorkspaceModelSource.builder().json(it).build() }).prepare(entryId)
        }.isSuccess
        val checker = ConsistencyWorkspace.fromSources(sources.map(::ModelSource))
            .check(entryId).findings.any { it.code == CK_MODEL_NOT_BUILDABLE }
        return "A12 ${if (a12) "built" else "not built"}, interpreter ${if (interpreter) "prepares" else "refuses"}, " +
            "checker ${if (checker) "not built" else "silent"}"
    }

    private fun ObjectNode.setMember(member: Triple<String, String, Any>) {
        ((get(member.first) as? ObjectNode) ?: putObject(member.first)).set<ObjectNode>(member.second, MAPPER.valueToTree(member.third))
    }

    private fun cover() = MAPPER.readTree(DocumentModelDraft.named("cover").locale("en_US")
        .rootGroup("Cover") { g -> g.numberField("Amount") { it.fractionDigits(0) } }.build().toJson()) as ObjectNode

    private fun permit() = MAPPER.readTree(DocumentModelDraft.named("permit").locale("en_US").include("component", "component")
        .rootGroup("Permit") { p -> p.mount("Details", "component") }.build().toJson()) as ObjectNode

    /** `cover` with a `Code` field of the library type `CoverCode`, which `types` declares. */
    private fun coded() = cover().also { root ->
        (root.path("header") as ObjectNode).putArray("modelReferences").addObject()
            .put("alias", "types").put("modelType", "document").put("purpose", "typeDefinitions").put("reference", "types")
        (root.at("/content/modelRoot/rootGroups/0/Group/elements") as ArrayNode).addObject()
            .put("type", "Field").put("id", "/Cover/Code").put("name", "Code").putObject("Field")
            .putObject("fieldType").put("type", "TypeDefType").putObject("TypeDefType").put("typeDefinitionId", "CoverCode")
    }

    private companion object {
        val MAPPER = ObjectMapper()
        val COMPONENT: String = DocumentModelDraft.named("component").locale("en_US")
            .rootGroup("Component") { c -> c.stringField("Description") {} }.build().toJson()

        /** A library: type definitions and no root group, which is the shape A12 expands. */
        val TYPES: String = (MAPPER.readTree(DocumentModelDraft.named("types").locale("en_US")
            .rootGroup("Types") { t -> t.stringField("Note") {} }.build().toJson()) as ObjectNode).also { root ->
            (root.at("/content/modelRoot/rootGroups") as ArrayNode).removeAll()
            (root.path("content") as ObjectNode).putArray("typeDefinitions").addObject().put("id", "CoverCode")
                .put("name", "CoverCode").put("revision", "1")
                .putObject("fieldType").put("type", "StringType").putObject("StringType").put("maxLength", 12)
        }.toString()

        const val BUILT = "A12 built, interpreter prepares, checker silent"
        const val NOT_BUILT = "A12 not built, interpreter refuses, checker not built"
    }
}
