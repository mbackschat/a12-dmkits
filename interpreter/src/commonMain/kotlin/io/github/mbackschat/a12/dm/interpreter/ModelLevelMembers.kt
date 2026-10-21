package io.github.mbackschat.a12.dm.interpreter

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * The first of a model's own model-level members A12's reader cannot bind, as its path, or null.
 *
 * `DocumentModelInfo` and `DocumentModelConfig` hold four members that are not text: the `Integer` `baseYear` and the
 * primitive `boolean`s `immutable`, `metaData` and `fieldRefByShortNameAllowed` (kernel `30.8.1` source). A12 reads
 * every model a workspace reaches whole, the entry, an included model and a type-definition library alike, so one
 * such member in any of them leaves the entry model unbuilt (KF591, KF577; `adapter.WorkspaceBlankTextDiffTest`).
 */
internal fun JsonObject.unbindableModelMember(): String? {
    val content = this["content"] as? JsonObject
    val info = content?.get("modelInfo") as? JsonObject
    if (info?.get("baseYear").bindJavaInt() == JavaIntBinding.Unbindable) return "content.modelInfo.baseYear"
    return MODEL_BOOLEANS.firstOrNull { (owner, member) ->
        configFlag((content?.get(owner) as? JsonObject)?.get(member)) == ConfigFlag.UNREADABLE
    }?.let { (owner, member) -> "content.$owner.$member" }
}

/** The model's value at [path], one [unbindableModelMember] returned, for a refusal message. */
internal fun JsonObject.modelMemberText(path: String): String =
    path.split('.').fold<String, JsonElement?>(this) { node, key -> (node as? JsonObject)?.get(key) }.toString()

private val MODEL_BOOLEANS = listOf(
    "modelInfo" to "immutable",
    "modelInfo" to "metaData",
    "modelConfig" to "fieldRefByShortNameAllowed",
)
