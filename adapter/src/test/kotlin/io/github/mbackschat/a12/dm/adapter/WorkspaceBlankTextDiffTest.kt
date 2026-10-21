package io.github.mbackschat.a12.dm.adapter

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import io.github.mbackschat.a12.dm.consistency.ConsistencyWorkspace
import io.github.mbackschat.a12.dm.consistency.DiagnosticCode
import io.github.mbackschat.a12.dm.consistency.ModelSource
import io.github.mbackschat.a12.dm.consistency.check
import io.github.mbackschat.a12.dm.core.ModelReader
import io.github.mbackschat.a12.dm.core.ModelResolver
import io.github.mbackschat.a12.dm.core.Severity
import io.github.mbackschat.a12.dm.interpreter.ModelWorkspace
import io.github.mbackschat.a12.dm.interpreter.WorkspaceModelSource
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.InvalidModelTimeZoneException
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Which identifying texts of spaces A12 accepts while it reads and expands a model, and that the interpreter's
 * workspace, the core reader and the portable checker accept the same ones (KERNEL-FINDINGS KF592).
 *
 * Six texts split every reading apart: the empty text, a space, a tab and an em space are blank to Java's
 * `Character.isWhitespace`, and the no-break and figure spaces are not. A12 reads a `header.id`, a `modelAlias`
 * and a type-definition id as plain text, refusing only the empty alias and the empty type-definition id; it
 * finds no included model when the include entry's `reference` is missing, null or Java-blank; and it refuses
 * every one of them as a time zone. Own domain (a permit and its component).
 */
class WorkspaceBlankTextDiffTest {

    private val spaces = listOf("", " ", "\t", "\u00A0", "\u2003", "\u2007")

    private fun label(text: String) = text.map { "U+%04X".format(it.code) }.toString()

    private fun javaBlank(text: String) = (text as java.lang.String).isBlank

    @Test
    fun anIdOfSpacesIdentifiesTheModelEverywhere() {
        val observed = spaces.associate { id ->
            val json = cover().also { (it.path("header") as ObjectNode).put("id", id) }.toString()
            val checker = check(ModelSource(json)).diagnostics.none { it.code == DiagnosticCode.SOURCE_HAS_NO_IDENTITY }
            label(id) to "A12 ${a12(json)}, interpreter ${prepares(id, json)}, checker ${if (checker) "identifies it" else "no identity"}"
        }
        assertEquals(observed.mapValues { "A12 admitted, interpreter prepares, checker identifies it" }, observed)
    }

    @Test
    fun onlyTheEmptyTypeDefinitionIdIsRefused() {
        val observed = (listOf("CoverType") + spaces).associate { id ->
            val json = cover().also { root ->
                (root.path("content") as ObjectNode).putArray("typeDefinitions").addObject().put("id", id)
                    .put("name", "CoverType").put("revision", "1")
                    .putObject("fieldType").put("type", "StringType").putObject("StringType")
                (root.at("/content/modelRoot/rootGroups/0/Group/elements") as ArrayNode).addObject()
                    .put("type", "Field").put("id", "/Cover/Note").put("name", "Note").putObject("Field")
                    .putObject("fieldType").put("type", "TypeDefType").putObject("TypeDefType").put("typeDefinitionId", id)
            }.toString()
            label(id) to "A12 ${a12(json)}, interpreter ${prepares("cover", json)}"
        }
        assertEquals(observed.mapValues { (id, _) ->
            if (id == "[]") "A12 refused, interpreter refuses" else "A12 admitted, interpreter prepares"
        }, observed)
    }

    @Test
    fun anIncludeFindsItsModelOnlyThroughAReferenceJavaReadsAsText() {
        val rows = linkedMapOf<String, Pair<ObjectNode, String>>()
        for (reference in listOf("component") + spaces) {
            rows["reference ${label(reference)}"] = permit().also { reference(it).put("reference", reference) } to reference
        }
        rows["no reference member"] = permit().also { reference(it).remove("reference") } to "component"
        rows["a null reference"] = permit().also { reference(it).putNull("reference") } to "component"
        rows["no modelReferences"] = permit().also { (it.path("header") as ObjectNode).remove("modelReferences") } to "component"
        val expected = rows.mapValues { (row, entry) ->
            if (row.startsWith("reference") && !javaBlank(entry.second)) MOUNTS else REFUSES
        }
        assertEquals(expected, rows.mapValues { (_, entry) -> mountObservation(entry.first.toString(), entry.second) })
    }

    @Test
    fun onlyTheEmptyAliasNamesNoInclude() {
        val observed = (listOf("component") + spaces).associate { alias ->
            val entry = permit().also { reference(it).put("alias", alias) }
            ((entry.at("/content/modelRoot/rootGroups/0/Group/elements/0/Group")) as ObjectNode).put("modelAlias", alias)
            label(alias) to mountObservation(entry.toString(), "component", checker = false)
        }
        assertEquals(observed.mapValues { (alias, _) -> if (alias == "[]") REFUSES_THREE else MOUNTS_THREE }, observed)
    }

    @Test
    fun noTextOfSpacesIsATimeZone() {
        val observed = spaces.associate { zone ->
            val json = cover().also { (it.path("content").path("modelConfig") as ObjectNode).put("timeZone", zone) }.toString()
            val codes = KernelConsistencyOracle().checkConsistency(json) { null }.map { it.toString() }
            val interpreter = runCatching { ModelLoader.load(json) }.exceptionOrNull()
            label(zone) to "A12 ${codes.any { "MVK_INVALID_TIME_ZONE" in it }}, interpreter ${interpreter is InvalidModelTimeZoneException}"
        }
        assertEquals(observed.mapValues { "A12 true, interpreter true" }, observed)
    }

    /** A12's expansion, the interpreter's preparation, the core reader and the checker's mount, the provider's id being [providerId]. */
    private fun mountObservation(entry: String, providerId: String, checker: Boolean = true): String {
        val provider = MAPPER.readTree(COMPONENT).also { (it.path("header") as ObjectNode).put("id", providerId) }.toString()
        val resolver = ModelResolver { reference -> if (reference == providerId) provider else null }
        val a12 = runCatching { KernelModelOracle.of(entry, resolver) }.isSuccess
        val interpreter = runCatching {
            ModelWorkspace.fromSources(listOf(entry, provider).map { WorkspaceModelSource.builder().json(it).build() }).prepare("permit")
        }.isSuccess
        val reader = runCatching { ModelReader.expand(entry, resolver) }.isSuccess
        val observed = "A12 ${a12.verb()}, interpreter ${interpreter.verb()}, reader ${reader.verb()}"
        if (!checker) return observed
        val unexpanded = ConsistencyWorkspace.fromSources(listOf(ModelSource(entry), ModelSource(provider))).check("permit")
            .gaps.any { it.at == "/Permit/Details" }
        return "$observed, checker ${(!unexpanded).verb()}"
    }

    private fun Boolean.verb() = if (this) "mounts" else "refuses"

    private fun a12(json: String): String {
        if (runCatching { KernelModelOracle.of(json) { null } }.isFailure) return "not built"
        val errors = KernelConsistencyOracle().checkConsistency(json) { null }.filter { it.severity() == Severity.ERROR }
        return if (errors.isEmpty()) "admitted" else "refused"
    }

    private fun prepares(entryId: String, json: String) = if (runCatching {
        ModelWorkspace.fromSources(listOf(WorkspaceModelSource.builder().json(json).build())).prepare(entryId)
    }.isSuccess) "prepares" else "refuses"

    private fun cover() = MAPPER.readTree(DocumentModelDraft.named("cover").locale("en_US")
        .rootGroup("Cover") { g -> g.numberField("Amount") { it.fractionDigits(0) } }.build().toJson()) as ObjectNode

    private fun permit() = MAPPER.readTree(DocumentModelDraft.named("permit").locale("en_US").include("component", "component")
        .rootGroup("Permit") { p -> p.mount("Details", "component") }.build().toJson()) as ObjectNode

    private fun reference(entry: ObjectNode) = (entry.at("/header/modelReferences") as ArrayNode)[0] as ObjectNode

    private companion object {
        val MAPPER = ObjectMapper()
        val COMPONENT: String = DocumentModelDraft.named("component").locale("en_US")
            .rootGroup("Component") { c -> c.stringField("Description") {} }.build().toJson()
        const val MOUNTS = "A12 mounts, interpreter mounts, reader mounts, checker mounts"
        const val REFUSES = "A12 refuses, interpreter refuses, reader refuses, checker refuses"
        const val MOUNTS_THREE = "A12 mounts, interpreter mounts, reader mounts"
        const val REFUSES_THREE = "A12 refuses, interpreter refuses, reader refuses"
    }
}
