package io.github.mbackschat.a12.dm.interpreter.workspace

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

internal enum class PreparedElementType(val wireName: String) {
    FIELD("Field"),
    GROUP("Group"),
    RULE("Rule"),
    COMPUTATION("Computation");

    companion object {
        fun fromWireName(value: String?): PreparedElementType? = when (value) {
            FIELD.wireName -> FIELD
            GROUP.wireName -> GROUP
            RULE.wireName -> RULE
            COMPUTATION.wireName -> COMPUTATION
            else -> null
        }
    }
}

internal enum class PreparedElementPosition {
    MODEL_ROOT,
    NESTED,
}

internal data class PreparedElementStructure(
    val node: JsonObject,
    val type: PreparedElementType,
    val name: String,
    val id: String,
    val body: JsonObject,
)

/**
 * Classify the one authoritative document-element tree for both raw workspace assembly and expanded loading.
 * The Kernel refuses missing identity members and malformed element bodies, except that it coerces present
 * non-string names/ids and admits an absent/null Field body. Portable preparation deliberately requires strings
 * and an object body because otherwise it would lose identity or fabricate a field definition.
 */
internal fun requirePreparedElementStructure(
    element: JsonElement,
    parentPath: String,
    position: PreparedElementPosition,
    invalid: (path: String, reason: String) -> Nothing,
): PreparedElementStructure {
    val node = element as? JsonObject
        ?: invalid(parentPath.ifEmpty { "/" }, "element must be a JSON object")
    val name = node.strictString("name")
    val id = node.strictString("id")
    val path = id?.takeIf { it.startsWith('/') }
        ?: name?.let { "$parentPath/$it" }
        ?: parentPath.ifEmpty { "/" }
    val type = PreparedElementType.fromWireName(node.strictString("type"))
        ?: invalid(path, "type must identify a supported document-model element")
    if (name == null) {
        invalid(path, "a document-model element requires a string name")
    }
    if (id == null) {
        invalid(path, "a document-model element requires a string id")
    }
    if (position == PreparedElementPosition.MODEL_ROOT && type != PreparedElementType.GROUP) {
        invalid(path, "a model root element must be a Group")
    }
    val body = node[type.wireName] as? JsonObject
        ?: invalid(path, "a ${type.wireName} element requires an object ${type.wireName} body")
    return PreparedElementStructure(node, type, name, id, body)
}

internal fun requirePreparedGroupChildren(
    body: JsonObject,
    path: String,
    invalid: (path: String, reason: String) -> Nothing,
): JsonArray? =
    when (val value = body["elements"]) {
        null -> null
        is JsonArray -> value
        else -> invalid(path, "Group.elements must be an array")
    }

internal val PREPARED_INCLUDE_DIRECTIVE_MEMBERS =
    setOf("modelAlias", "includeLevel", "excludeRules", "excludeComputations")
