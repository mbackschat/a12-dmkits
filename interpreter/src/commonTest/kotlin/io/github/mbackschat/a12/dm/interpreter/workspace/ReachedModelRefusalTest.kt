package io.github.mbackschat.a12.dm.interpreter.workspace

import io.github.mbackschat.a12.dm.interpreter.ModelPreparationDiagnosticCode
import io.github.mbackschat.a12.dm.interpreter.ModelPreparationError
import io.github.mbackschat.a12.dm.interpreter.ModelWorkspace
import io.github.mbackschat.a12.dm.interpreter.WorkspaceModelSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What leaves A12 unable to build the entry refuses preparation in whichever model the entry reaches: the entry, an
 * included model, a model that one includes, and a type-definition library (KF594). That is a model-level member A12
 * cannot bind or a locale code it cannot parse, an entry locale a reached model does not declare, a root group in a
 * library, and a locale an included model declares twice or as the empty code. A12's side of it is
 * `adapter.WorkspaceReachedModelDiffTest`; this holds the same reading on every platform the workspace runs on.
 */
class ReachedModelRefusalTest {

    private val caseId = "imported-typedef-nested-single"
    private val members = linkedMapOf(
        "content.modelInfo.baseYear" to JsonPrimitive("NULL"),
        "content.modelInfo.immutable" to JsonPrimitive(1.5),
        "content.modelInfo.metaData" to JsonPrimitive(1.5),
        "content.modelConfig.fieldRefByShortNameAllowed" to JsonPrimitive(1.5),
    )

    @Test
    fun eachReachedModelRefusesOnEachUnbindableMember() {
        val sources = WorkspacePreparationFixtureJson.sources(caseId)
        val observed = sources.keys.flatMap { modelId ->
            members.map { (path, value) ->
                "$modelId $path" to refusal(sources + (modelId to sources.getValue(modelId).setting(path, value)))
            }
        }.toMap()
        // Each row names the model and member it edited, so the refusal must name exactly those.
        assertEquals(observed.mapValues { it.key }, observed)
    }

    @Test
    fun theUntouchedWorkspacePrepares() {
        assertEquals("prepares", refusal(WorkspacePreparationFixtureJson.sources(caseId)))
    }

    /**
     * Every model declares `de_DE` beside `en_US` but one; a model declaring a third as well is the control. An included
     * model leaving it out refuses, and the type-definition library `permit-types` prepares: kernel 31.1.1 expands a
     * library whatever locales it declares (`adapter.WorkspaceReachedModelDiffTest`).
     */
    @Test
    fun eachIncludedModelMissingAnEntryLocaleRefuses() {
        val sources = WorkspacePreparationFixtureJson.sources(caseId)
        fun locales(vararg codes: String) = JsonArray(codes.map { JsonObject(mapOf("code" to JsonPrimitive(it))) })
        val bilingual = sources.mapValues { (_, json) -> json.setting("header.locales", locales("en_US", "de_DE")) }
        val entryId = WorkspacePreparationFixtureJson.entryModelId(caseId)
        val observed = sources.keys.filter { it != entryId }.associateWith { narrow ->
            refusal(bilingual + (narrow to sources.getValue(narrow).setting("header.locales", locales("en_US"))))
        }
        assertEquals(observed.mapValues { if (it.key == "permit-types") "prepares" else "${it.key} header.locales" }, observed)
        assertEquals("prepares", refusal(bilingual.mapValues { (id, json) ->
            if (id == entryId) json else json.setting("header.locales", locales("en_US", "de_DE", "fr_FR"))
        }))
    }

    /**
     * A locale code A12 cannot parse refuses in every model; a locale declared twice, in one spelling or two, and the
     * empty code refuse only in an included model, here `permit-section` and the `inspection-checks` it includes. The
     * entry and the library declaring them prepare, as A12 builds them.
     */
    @Test
    fun aLocaleCodeRefusesByTheModelDeclaringIt() {
        val sources = WorkspacePreparationFixtureJson.sources(caseId)
        fun locales(vararg codes: String) = JsonArray(codes.map { JsonObject(mapOf("code" to JsonPrimitive(it))) })
        val sets = linkedMapOf("unreadable" to locales("en_US", "en_us"), "twice" to locales("en_US", "en-US"),
            "empty" to locales("en_US", ""))
        val observed = sets.flatMap { (label, codes) ->
            sources.keys.map { modelId -> "$modelId $label" to refusal(sources + (modelId to sources.getValue(modelId).setting("header.locales", codes))) }
        }.toMap()
        val included = setOf("permit-section", "inspection-checks")
        assertEquals(observed.mapValues { (row, _) ->
            val (modelId, label) = row.split(' ')
            if (label == "unreadable" || modelId in included) "$modelId header.locales" else "prepares"
        }, observed)
    }

    /** Kernel 31.1.1 expands a type-definition library whatever root groups it holds (`adapter.WorkspaceReachedModelDiffTest`). */
    @Test
    fun aTypeDefinitionLibraryWithARootGroupPrepares() {
        val sources = WorkspacePreparationFixtureJson.sources(caseId)
        val group = Json.parseToJsonElement("""{"type":"Group","id":"/Types","name":"Types","Group":{"elements":[]}}""")
        assertEquals("prepares",
            refusal(sources + ("permit-types" to sources.getValue("permit-types").setting("content.modelRoot.rootGroups", JsonArray(listOf(group))))))
    }

    /** The refused model and member, or `prepares`. */
    private fun refusal(sources: Map<String, String>): String = try {
        ModelWorkspace.fromSources(sources.values.map { WorkspaceModelSource.builder().json(it).build() })
            .prepare(WorkspacePreparationFixtureJson.entryModelId(caseId))
        "prepares"
    } catch (error: ModelPreparationError) {
        val diagnostic = error.diagnostics.single()
        assertEquals(ModelPreparationDiagnosticCode.INVALID_MODEL_SOURCE, diagnostic.code)
        "${diagnostic.modelId} ${diagnostic.path}"
    }

    private fun String.setting(path: String, value: JsonElement): String {
        val keys = path.split('.')
        fun JsonObject.set(at: List<String>): JsonObject = JsonObject(
            this + (at.first() to if (at.size == 1) value else ((this[at.first()] as? JsonObject) ?: JsonObject(emptyMap())).set(at.drop(1))),
        )
        return Json.parseToJsonElement(this).jsonObject.set(keys).toString()
    }
}
