package io.github.mbackschat.a12.dm.interpreter.workspace

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
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
 * Kernel 31.1.1 refuses missing identity members and every non-object Field, Rule or Computation body, an absent
 * one included, and coerces present non-string names/ids; portable preparation deliberately requires strings,
 * because otherwise it would lose identity.
 *
 * A Group body is the exception: the kernel reads a missing or `null` one as an empty group on every carrier and
 * refuses any other non-object (KERNEL-FINDINGS KF3, `adapter.WorkspaceIncludeStructureKernelTest`). The returned
 * [PreparedElementStructure.node] then carries that empty body, so no later stage meets a group without one.
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
    return when (val body = node[type.wireName]) {
        is JsonObject -> PreparedElementStructure(node, type, name, id, body)
        null, JsonNull -> {
            if (type != PreparedElementType.GROUP) {
                invalid(path, "a ${type.wireName} element requires an object ${type.wireName} body")
            }
            val empty = JsonObject(emptyMap())
            PreparedElementStructure(JsonObject(node + (type.wireName to empty)), type, name, id, empty)
        }
        else -> invalid(path, "a ${type.wireName} element requires an object ${type.wireName} body")
    }
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

/** The member a DM 29 mount group carries; `null` declares no mount (KF651). */
internal const val INCLUDE_CONFIG_MEMBER = "includeConfig"

/**
 * The DM 28 mount members, which A12 has ignored since DM 29.0 moved them into [INCLUDE_CONFIG_MEMBER] (KF651). A12
 * reads a group carrying one as plain, so the mount or its option is lost without a notification.
 */
internal val DM28_MOUNT_MEMBERS = setOf("modelAlias", "includeLevel", "excludeRules", "excludeComputations")

/** The include member this group body still carries, or null when it is a plain group. */
internal fun JsonObject.includeDirectiveMember(): String? =
    INCLUDE_CONFIG_MEMBER.takeIf { get(it).let { value -> value != null && value !is JsonNull } }
        ?: DM28_MOUNT_MEMBERS.firstOrNull(::containsKey)
