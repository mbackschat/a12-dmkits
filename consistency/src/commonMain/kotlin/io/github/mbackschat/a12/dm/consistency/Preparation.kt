package io.github.mbackschat.a12.dm.consistency

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * One declared element of a DocumentModel, as the model declares it.
 *
 * [path] is the element's `id` verbatim: A12 stores the canonical path there, so the checker reads paths and
 * never assembles them. [kind] is the `type` discriminator verbatim rather than a checker-side enum, so a
 * kind this build does not handle stays a located declaration instead of a silently dropped one.
 */
data class DeclaredElement(val path: String, val name: String, val kind: String)

/**
 * An admitted source's declarations, in declaration order — the fact base every later phase reads.
 *
 * @param fieldRefByShortNameAllowed the model's `content.modelConfig.fieldRefByShortNameAllowed`. It decides
 *     whether a bare reference the declaring group does not have may resolve to a unique model-wide field
 *     short name, so reference resolution cannot be done without it.
 */
data class PreparedModel(
    val elements: List<DeclaredElement>,
    val fieldRefByShortNameAllowed: Boolean,
)

/**
 * The four element kinds the kernel's `ElementDeserializer` switch admits; any other `type` is its
 * `IllegalArgumentException`. Only [GROUP] carries children, which is why descent is decided by the
 * discriminator and not by whichever envelope happens to hold an `elements` array.
 */
private enum class DeclaredKind {
    FIELD, GROUP, RULE, COMPUTATION;

    companion object {
        fun of(type: String): DeclaredKind? = entries.firstOrNull { it.name == type.uppercase() }
    }
}

/** Read [Admission.Admitted]'s retained tree into the declarations the checker works from. */
fun prepare(admitted: Admission.Admitted): PreparedModel {
    val content = admitted.parsed.jsonObject["content"]?.jsonObject
    val elements = mutableListOf<DeclaredElement>()
    content?.get("modelRoot")?.jsonObject?.get("rootGroups")?.jsonArray
        ?.forEach { collect(it, elements) }
    return PreparedModel(
        elements = elements,
        fieldRefByShortNameAllowed = content?.get("modelConfig")?.jsonObject
            ?.get("fieldRefByShortNameAllowed")?.jsonPrimitive?.boolean ?: false,
    )
}

private fun collect(node: JsonElement, into: MutableList<DeclaredElement>) {
    val element = node.jsonObject
    val kind = element.getValue("type").jsonPrimitive.content
    into += DeclaredElement(
        path = element.getValue("id").jsonPrimitive.content,
        name = element.getValue("name").jsonPrimitive.content,
        kind = kind,
    )
    when (DeclaredKind.of(kind)) {
        // The envelope is keyed by `type` and is OPTIONAL — the kernel default-constructs the element when it
        // is absent — so a group without one simply declares no children.
        DeclaredKind.GROUP -> element[kind]?.jsonObject?.children()?.forEach { collect(it, into) }
        DeclaredKind.FIELD, DeclaredKind.RULE, DeclaredKind.COMPUTATION, null -> Unit
    }
}

private fun JsonObject.children(): List<JsonElement> = this["elements"]?.jsonArray.orEmpty()
