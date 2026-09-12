package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.consistency.syntax.ConditionLanguage
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

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
) {
    /** The model this group mounts, when it declares one — `Group.modelAlias`. */
    val modelAlias: String?
        get() = ((declaration[kind] as? JsonObject)?.get("modelAlias") as? JsonPrimitive)?.contentOrNull

    /**
     * Whether this group mounts another model that has NOT been expanded into it. The test is
     * `core.ModelReader`'s own — an alias with no `elements` — because an already-mounted group carries both
     * and re-expanding it is what the Kernel's idempotency guard prevents.
     */
    val mountsAnotherModel: Boolean
        get() = !modelAlias.isNullOrEmpty() &&
            ((declaration[kind] as? JsonObject)?.get("elements") as? JsonArray).isNullOrEmpty()
}

private fun JsonArray?.isNullOrEmpty(): Boolean = this == null || this.isEmpty()

/**
 * An admitted source's declarations, in declaration order — the fact base every later phase reads.
 *
 * @param conditionLanguage the model's `content.modelConfig.conditionLanguage.code`, English when the model
 *     declares none. It decides which names in a condition are operator keywords and which are entity
 *     references, so no condition can be read without it.
 * @param unsupportedConditionLanguage the declared code when A12 does not support it — the model is then
 *     refused outright rather than read in some fallback language, so no condition may be analysed at all.
 * @param fieldRefByShortNameAllowed the model's `content.modelConfig.fieldRefByShortNameAllowed`. It decides
 *     whether a bare reference the declaring group does not have may resolve to a unique model-wide field
 *     short name, so reference resolution cannot be done without it.
 * @param diagnostics what the checker could not read. Preparation reports and skips rather than failing, so
 *     one broken element cannot mask its siblings' findings — and no input makes [prepare] raise.
 */
data class PreparedModel(
    val elements: List<DeclaredElement>,
    val fieldRefByShortNameAllowed: Boolean,
    val conditionLanguage: ConditionLanguage = ConditionLanguage.DEFAULT,
    val unsupportedConditionLanguage: String? = null,
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
    val declaredCode = content.conditionLanguageCode()?.takeIf { it.isNotBlank() }
    val declaredLanguage = declaredCode?.let { ConditionLanguage.of(it) }
    val collector = Collector(admitted.modelId, admitted.source.diagnosticLabel)
    val root = (content?.get("modelRoot") as? JsonObject)?.get("rootGroups")
    collector.each(root, at = null, what = "content.modelRoot.rootGroups") { collector.collect(it, "", null) }
    return PreparedModel(
        elements = collector.elements,
        fieldRefByShortNameAllowed = collector.flag(content),
        conditionLanguage = declaredLanguage ?: ConditionLanguage.DEFAULT,
        unsupportedConditionLanguage = declaredCode.takeIf { it != null && declaredLanguage == null },
        diagnostics = collector.diagnostics,
    )
}

private class Collector(private val modelId: String, private val diagnosticLabel: String?) {
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

    /**
     * `fieldRefByShortNameAllowed`, absent or unreadable meaning off — never a raised conversion.
     *
     * The QUOTED form is accepted, because the Kernel accepts it: a model declaring `"true"` enables the
     * model-wide short-name tier (measured, `rule check`). Refusing it here would flip every short-name
     * verdict on a model A12 reads — the read-path half of the over-strictness rule, and the same coercion
     * [text] applies to `type`/`id`/`name`.
     */
    fun flag(content: JsonObject?): Boolean {
        val declared = (content?.get("modelConfig") as? JsonObject)?.get("fieldRefByShortNameAllowed")
            ?: return false
        return when ((declared as? JsonPrimitive)?.contentOrNull) {
            "true" -> true
            "false" -> false
            else -> {
                malformed(null, "`fieldRefByShortNameAllowed` must be a boolean, or a string spelling one")
                false
            }
        }
    }

    private fun malformed(at: String?, explain: String) {
        // The caller's label rides along, the same way admission's diagnostics carry it: a consumer checking
        // several sources needs the FILE, not only the model id it happened to declare.
        diagnostics += Diagnostic(DiagnosticCode.DECLARATION_NOT_WELL_FORMED, modelId, explain, at, diagnosticLabel)
    }
}

/**
 * A member read the way the Kernel reads it: `ElementDeserializer` takes `type`, `id` and `name` through
 * Jackson's `asText()`, which COERCES a non-string scalar rather than refusing it, so `{"id": 5}` is an
 * element the Kernel reads with id `"5"`. Refusing it here would drop a declaration A12 accepts — the read
 * path is where an over-strict check makes a legal model unreadable rather than merely unauthorable.
 *
 * JSON `null` is treated as absent instead: its Jackson coercion is not measured here, and reporting the
 * member as missing is the conservative arm, since that is a finding rather than a silent substitution.
 */
private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

private operator fun JsonElement?.get(key: String): JsonElement? = (this as? JsonObject)?.get(key)

/**
 * The declared condition language code, or `null` when the model does not state one — read leniently on
 * purpose, because an unreadable code is not a reason to refuse the model when A12 itself defaults.
 */
private fun JsonObject?.conditionLanguageCode(): String? =
    ((this?.get("modelConfig") as? JsonObject)?.get("conditionLanguage") as? JsonObject)
        ?.get("code")?.let { (it as? JsonPrimitive)?.contentOrNull }
