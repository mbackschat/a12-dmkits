package io.github.mbackschat.a12.dm.consistency

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Once membership passes, an absent category precedes the enum/type check (KF413, KF501). */
internal fun TrackedFactBase.messageCategoryDefect(path: String, category: String?): String? {
    if (category == null) return "NO_CATEGORY"
    val field = declarationAt(path) ?: return null
    if (field.declaredFieldType() != FieldType.ENUMERATION) return "FIELD_NO_ENUM"
    val declared = field.enumerationBody()?.categoryNames() ?: return null
    return if (category.trim('\'') in declared) null else "INVALID_CATEGORY"
}

/** The category names this enumeration declares — empty where it declares none, which is not a failure to read. */
private fun JsonObject.categoryNames(): Set<String> =
    (this["categories"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
        .mapNotNullTo(mutableSetOf()) { (it["name"] as? JsonPrimitive)?.takeIf { n -> n.isString }?.content }

private fun JsonArray?.orEmpty(): List<kotlinx.serialization.json.JsonElement> = this ?: emptyList()
