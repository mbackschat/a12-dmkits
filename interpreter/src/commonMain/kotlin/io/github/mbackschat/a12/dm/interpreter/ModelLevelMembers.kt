package io.github.mbackschat.a12.dm.interpreter

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The first of a model's own model-level members A12's reader cannot bind, as its path, or null.
 *
 * Its frame comes first ([unbindableFrameMember]): a member missing or of a kind the reader cannot bind.
 * The header's `locales` binds each code as a locale, so one A12 cannot parse (`en_us`) refuses; the empty code is
 * read, and what it costs depends on which model declares it ([a12LocaleKeyOrNull]). `DocumentModelInfo` and
 * `DocumentModelConfig` hold four members that are not text: the `Integer` `baseYear` and the primitive `boolean`s
 * `immutable`, `metaData` and `fieldRefByShortNameAllowed` (kernel `30.8.1` source). A12 reads every model a
 * workspace reaches whole, the entry, an included model and a type-definition library alike, so one such member in
 * any of them leaves the entry model unbuilt (KF594; `adapter.WorkspaceReachedModelDiffTest`).
 */
internal fun JsonObject.unbindableModelMember(): String? {
    unbindableFrameMember()?.let { return it }
    if (declaredLocaleCodes().any { it.isNotEmpty() && a12LocaleKeyOrNull(it) == null }) return "header.locales"
    val content = this["content"] as? JsonObject
    val info = content?.get("modelInfo") as? JsonObject
    if (info?.get("baseYear").bindJavaInt() == JavaIntBinding.Unbindable) return "content.modelInfo.baseYear"
    return MODEL_BOOLEANS.firstOrNull { (owner, member) ->
        configFlag((content?.get(owner) as? JsonObject)?.get(member)) == ConfigFlag.UNREADABLE
    }?.let { (owner, member) -> "content.$owner.$member" }
}

/** What the model holds at [path], one [unbindableModelMember] returned, for a refusal message. */
internal fun JsonObject.modelMemberState(path: String): String =
    path.split('.').fold<String, JsonElement?>(this) { node, key ->
        val name = key.substringBefore('[')
        val member = (node as? JsonObject)?.get(name)
        key.substringAfter('[', "").removeSuffix("]").asciiIntOrNull()?.let { (member as? JsonArray)?.getOrNull(it) } ?: member
    }?.let { "holds $it" } ?: "is absent"

/**
 * The declared condition-language code, or null when the model states none. A12 binds the member as a locale, so a
 * bare text declares it as an object's `code` does, and `"de_DE"` reads German conditions (KF650); a shape its
 * binding refuses never arrives here ([unbindableFrameMember]).
 */
internal fun JsonObject.conditionLanguageCode(): String? =
    when (val declared = ((this["content"] as? JsonObject)?.get("modelConfig") as? JsonObject)?.get("conditionLanguage")) {
        is JsonObject -> (declared["code"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        is JsonPrimitive -> declared.takeIf { it.isString }?.content
        else -> null
    }

private val MODEL_BOOLEANS = listOf(
    "modelInfo" to "immutable",
    "modelInfo" to "metaData",
    "modelConfig" to "fieldRefByShortNameAllowed",
)
