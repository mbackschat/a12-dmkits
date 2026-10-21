package io.github.mbackschat.a12.dm.interpreter.workspace

import io.github.mbackschat.a12.dm.interpreter.ModelPreparationDiagnosticCode
import io.github.mbackschat.a12.dm.interpreter.ModelPreparationError
import io.github.mbackschat.a12.dm.interpreter.ModelWorkspace
import io.github.mbackschat.a12.dm.interpreter.WorkspaceModelSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A model-level member A12 cannot bind refuses preparation in whichever model the entry reaches: the entry, an
 * included model, a model that one includes, and a type-definition library (KF594). A12's side of it is
 * `adapter.WorkspaceBlankTextDiffTest`; this holds the same reading on every platform the workspace runs on.
 */
class ReachedModelMemberBindingTest {

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
