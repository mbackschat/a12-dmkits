package io.github.mbackschat.a12.dm.consistency

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * A reference A12 declares as a structured member rather than inside condition text.
 *
 * @param declaredAt the path of the declaration that carries it.
 * @param member the member it was read from, so a report can say which one did not resolve.
 * @param role which rejection A12 issues when it does not resolve — the two members differ.
 * @param target what it resolved to.
 */
data class DeclaredReference(
    val declaredAt: String,
    val member: String,
    val role: ReferenceRole,
    val target: Reference,
)

/**
 * The two DSL-carrier reference members: a rule's `errorEntityRelPath` and a computation's
 * `computedFieldRelPath`. A12 declares two further relative references this does NOT read —
 * `Group.indexFieldName` and `Group.sortFields[].name` — and each report says so as a coverage gap.
 *
 * These need no condition parser and the Kernel checks them — an error field that does not resolve is
 * `MVK_ERROR_FIELD_MISSING`. They resolve from the **declaring element**, not from its group: a rule at
 * `/Order/Check` reaches its sibling field as `../Code`, so a path written without that step names a child
 * of the rule and correctly fails. On a root-level rule the two readings coincide, which is why the
 * one-level-off case is the discriminator worth keeping.
 */
fun PreparedModel.declaredReferences(): List<DeclaredReference> = elements.mapNotNull { element ->
    val declared = DECLARED_BY_KIND[element.kind] ?: return@mapNotNull null
    val relative = (element.declaration[element.kind] as? JsonObject)
        ?.get(declared.first)?.let { (it as? JsonPrimitive)?.contentOrNull }
        ?: return@mapNotNull null
    // No rehosting here, unlike a condition reference: `errorEntityRelPath` and `computedFieldRelPath` are
    // strictly RELATIVE — an absolute spelling is "illegal relPath" at conversion and the model never builds
    // (measured 2026-09-12) — and a relative path is move-invariant under mounting, because the provider's
    // root group is REPLACED by the mount group rather than added above it.
    DeclaredReference(element.path, declared.first, declared.second, resolveRelative(element.path, relative))
}

private val DECLARED_BY_KIND = mapOf(
    "Rule" to ("errorEntityRelPath" to ReferenceRole.ERROR_TARGET),
    "Computation" to ("computedFieldRelPath" to ReferenceRole.COMPUTED_TARGET),
)

/**
 * Resolve [relative] against the declaring element at [from] — `..` climbs one step, any other segment
 * descends. Climbing past the root yields a path nothing declares rather than a silently clamped one, so an
 * over-long relative path is reported instead of quietly naming a root element.
 */
private fun PreparedModel.resolveRelative(from: String, relative: String): Reference {
    val segments = from.split('/').filter { it.isNotEmpty() }.toMutableList()
    var climbedPastRoot = false
    relative.split('/').filter { it.isNotEmpty() }.forEach { step ->
        when (step) {
            ".." -> if (segments.isEmpty()) climbedPastRoot = true else segments.removeAt(segments.size - 1)
            else -> segments += step
        }
    }
    val path = "/" + segments.joinToString("/")
    return when {
        // Climbing past the root is a DIFFERENT outcome from naming nothing, and worse: the Kernel raises
        // inside `ElementPathUtils.absPath` while deserializing, so the model never loads and no check runs.
        climbedPastRoot -> Reference.AboveRoot(relative)
        elements.any { it.path == path } -> Reference.Resolved(path)
        else -> Reference.Undeclared(path)
    }
}
