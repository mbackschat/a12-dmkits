package io.github.mbackschat.a12.dm.consistency

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
 *
 * @param declaration the kind's own authored envelope, retained unchanged and absent when the model omits it
 *     (which the kernel's reader permits). It is kept whole rather than projected into per-kind fields so an
 *     invalid declaration stays inspectable and no parallel A12 model is invented; the phase that needs a
 *     projection derives one.
 */
data class DeclaredElement(
    val path: String,
    val name: String,
    val kind: String,
    val declaration: JsonObject? = null,
)

/**
 * An admitted source's declarations, in declaration order — the fact base every later phase reads.
 *
 * @param fieldRefByShortNameAllowed the model's `content.modelConfig.fieldRefByShortNameAllowed`. It decides
 *     whether a bare reference the declaring group does not have may resolve to a unique model-wide field
 *     short name, so reference resolution cannot be done without it.
 * @param diagnostics declarations the checker could not read. Preparation reports and skips them rather than
 *     failing, so one broken element cannot mask its siblings' findings.
 */
data class PreparedModel(
    val elements: List<DeclaredElement>,
    val fieldRefByShortNameAllowed: Boolean,
    val diagnostics: List<Diagnostic> = emptyList(),
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
    val content = (admitted.parsed as? JsonObject)?.get("content") as? JsonObject
    val collector = Collector(admitted.source.modelId)
    (content?.get("modelRoot") as? JsonObject)?.get("rootGroups")?.jsonArray
        ?.forEach { collector.collect(it, enclosing = null) }
    return PreparedModel(
        elements = collector.elements,
        fieldRefByShortNameAllowed = (content?.get("modelConfig") as? JsonObject)
            ?.get("fieldRefByShortNameAllowed")?.jsonPrimitive?.boolean ?: false,
        diagnostics = collector.diagnostics,
    )
}

private class Collector(private val modelId: String) {
    val elements = mutableListOf<DeclaredElement>()
    val diagnostics = mutableListOf<Diagnostic>()

    /** @param enclosing the nearest readable ancestor path, used to locate a declaration that cannot name itself. */
    fun collect(node: JsonElement, enclosing: String?) {
        val element = node as? JsonObject ?: return malformed(enclosing, "a declaration must be a JSON object")
        val kind = element.text("type")
            // Without `type` the envelope cannot be found either — the one failure that does hide children.
            ?: return malformed(enclosing, "a declaration must carry its `type` discriminator")
        val envelope = element[kind] as? JsonObject
        val path = element.text("id")
        val name = element.text("name")
        if (path == null || name == null) {
            malformed(enclosing, "a declaration must carry both `id` and `name`")
        } else {
            elements += DeclaredElement(path, name, kind, envelope)
        }
        when (DeclaredKind.of(kind)) {
            // Descend even from a declaration we could not read: its children name themselves, and dropping
            // a subtree would turn one broken header into a silent hole in the analysis.
            DeclaredKind.GROUP -> envelope?.get("elements")?.jsonArray
                ?.forEach { collect(it, enclosing = path ?: enclosing) }
            DeclaredKind.FIELD, DeclaredKind.RULE, DeclaredKind.COMPUTATION, null -> Unit
        }
    }

    private fun malformed(at: String?, explain: String) {
        diagnostics += Diagnostic(DiagnosticCode.DECLARATION_NOT_WELL_FORMED, modelId, explain, at)
    }
}

private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
