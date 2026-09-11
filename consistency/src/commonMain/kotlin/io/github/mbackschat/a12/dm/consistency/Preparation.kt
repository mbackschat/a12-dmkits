package io.github.mbackschat.a12.dm.consistency

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * One declared element of a DocumentModel, as the model declares it.
 *
 * [path] is the element's canonical path — the join of the `name` segments from the root, which is the
 * Kernel's own `getFullName()` and the thing a condition references. It is deliberately NOT the element's
 * [id]: `id` is an **opaque identifier** in a disjoint namespace, equal to the path only in fixtures we
 * authored ourselves. Real models carry hash ids, and keying by `id` resolved ~0% of a foreign model's rule
 * references before `ModelIndex` was re-keyed by the name-path (KERNEL-INTEGRATION-NOTES, "`getId()` is an
 * *opaque identifier*, NOT the path").
 *
 * @param kind the `type` discriminator verbatim, matched case-sensitively wherever it is interpreted, since
 *     the Kernel's own reader switches on the exact spelling.
 * @param declaration the whole authored element node, retained unchanged — envelope, annotations and
 *     descriptions alike. Keeping the node rather than projecting per-kind fields is what lets an invalid
 *     declaration stay inspectable without inventing a parallel A12 model.
 */
data class DeclaredElement(
    val path: String,
    val name: String,
    val kind: String,
    val id: String?,
    val declaration: JsonObject,
)

/**
 * An admitted source's declarations, in declaration order — the fact base every later phase reads.
 *
 * @param fieldRefByShortNameAllowed the model's `content.modelConfig.fieldRefByShortNameAllowed`. It decides
 *     whether a bare reference the declaring group does not have may resolve to a unique model-wide field
 *     short name, so reference resolution cannot be done without it.
 * @param diagnostics what the checker could not read. Preparation reports and skips rather than failing, so
 *     one broken element cannot mask its siblings' findings — and no input makes [prepare] raise.
 */
data class PreparedModel(
    val elements: List<DeclaredElement>,
    val fieldRefByShortNameAllowed: Boolean,
    val diagnostics: List<Diagnostic> = emptyList(),
)

/**
 * The four element kinds the Kernel's `ElementDeserializer` switch admits; any other `type` is its
 * `IllegalArgumentException` on the whole source. Only [GROUP] carries children, which is why descent is
 * decided by the discriminator and not by whichever envelope happens to hold an `elements` array.
 */
private enum class DeclaredKind(val wire: String) {
    FIELD("Field"), GROUP("Group"), RULE("Rule"), COMPUTATION("Computation");

    companion object {
        /** Matched on the wire spelling, because the Kernel's switch is case-SENSITIVE: `"group"` hits its
         *  `default:` arm and makes the whole source unreadable, where `"Group"` is a group. */
        fun of(type: String): DeclaredKind? = entries.firstOrNull { it.wire == type }
    }
}

/** Read [Admission.Admitted]'s retained tree into the declarations the checker works from. */
fun prepare(admitted: Admission.Admitted): PreparedModel {
    val content = (admitted.parsed as? JsonObject)?.get("content") as? JsonObject
    val collector = Collector(admitted.source.modelId)
    val root = (content?.get("modelRoot") as? JsonObject)?.get("rootGroups")
    collector.each(root, at = null, what = "content.modelRoot.rootGroups") { collector.collect(it, "", null) }
    return PreparedModel(
        elements = collector.elements,
        fieldRefByShortNameAllowed = collector.flag(content),
        diagnostics = collector.diagnostics,
    )
}

private class Collector(private val modelId: String) {
    val elements = mutableListOf<DeclaredElement>()
    val diagnostics = mutableListOf<Diagnostic>()

    /**
     * @param parentPath the enclosing element's canonical path, which this element's `name` extends.
     * @param enclosing the nearest path that could be derived, used to locate a declaration too broken to
     *     name itself.
     */
    fun collect(node: JsonElement, parentPath: String, enclosing: String?) {
        val element = node as? JsonObject ?: return malformed(enclosing, "a declaration must be a JSON object")
        val kind = element.text("type")
        val name = element.text("name")
        // `type` names the envelope and `name` places the subtree, so a declaration missing either cannot be
        // descended into. `id` is not in that set: it identifies the element but no longer locates it.
        if (kind == null || name == null) {
            return malformed(enclosing, "a declaration must carry both `type` and `name`")
        }
        val path = "$parentPath/$name"
        val id = element.text("id")
        if (id == null) {
            malformed(path, "a declaration must carry an `id`")
        }
        elements += DeclaredElement(path, name, kind, id, element)
        // The envelope is optional — the Kernel default-constructs the element when it is absent — but a
        // present one must be an object, since the Kernel binds it to the kind's type.
        val envelope = element[kind]
        if (envelope != null && envelope !is JsonObject) {
            return malformed(path, "`$kind` must be a JSON object")
        }
        when (DeclaredKind.of(kind)) {
            DeclaredKind.GROUP -> each(envelope?.get("elements"), path, "$path's `elements`") {
                collect(it, path, path)
            }
            // An unrecognized `type` makes the whole source unreadable to the Kernel, so it is reported here
            // rather than passing as an inert declaration this build merely does not handle.
            null -> malformed(path, "`$kind` is not an A12 element type")
            DeclaredKind.FIELD, DeclaredKind.RULE, DeclaredKind.COMPUTATION -> Unit
        }
    }

    /** Iterate a member that must be an array, diagnosing rather than raising when it is not. */
    fun each(value: JsonElement?, at: String?, what: String, body: (JsonElement) -> Unit) {
        when (value) {
            null -> Unit
            is JsonArray -> value.forEach(body)
            else -> malformed(at, "$what must be a JSON array")
        }
    }

    /** `fieldRefByShortNameAllowed`, absent or unreadable meaning off — never a raised conversion. */
    fun flag(content: JsonObject?): Boolean {
        val declared = (content?.get("modelConfig") as? JsonObject)?.get("fieldRefByShortNameAllowed")
            ?: return false
        return when ((declared as? JsonPrimitive)?.takeIf { !it.isString }?.content) {
            "true" -> true
            "false" -> false
            else -> {
                malformed(null, "`fieldRefByShortNameAllowed` must be a JSON boolean")
                false
            }
        }
    }

    private fun malformed(at: String?, explain: String) {
        diagnostics += Diagnostic(DiagnosticCode.DECLARATION_NOT_WELL_FORMED, modelId, explain, at)
    }
}

private fun JsonObject.text(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

private operator fun JsonElement?.get(key: String): JsonElement? = (this as? JsonObject)?.get(key)
