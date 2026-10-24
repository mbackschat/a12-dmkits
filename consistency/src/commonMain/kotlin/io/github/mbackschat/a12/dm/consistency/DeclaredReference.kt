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
 * @param written the relative path exactly as authored, which is what a reader has to find and edit. Kept
 *     beside the resolved target because a report naming only the resolution sends its reader looking for a
 *     string the model does not contain.
 */
data class DeclaredReference(
    val declaredAt: String,
    val member: String,
    val role: ReferenceRole,
    val target: Reference,
    val written: String,
)

/**
 * The two DSL-carrier reference members: a rule's `errorEntityRelPath` and a computation's
 * `computedFieldRelPath`. A12 declares two further relative references — `Group.indexFieldName` and
 * `Group.sortFields[].name` — which are resolved by [groupOrderingFindings] and NOT here: they get no
 * short-name fallback, and each carries a code of its own, so the roles below do not apply to them.
 *
 * These need no condition parser and the Kernel checks them — an error field that does not resolve is
 * `MVK_ERROR_FIELD_MISSING`. They resolve from the **declaring element**, not from its group: a rule at
 * `/Order/Check` reaches its sibling field as `../Code`, so a path written without that step names a child
 * of the rule and correctly fails. On a root-level rule the two readings coincide, which is why the
 * one-level-off case is the discriminator worth keeping.
 */
fun PreparedModel.declaredReferences(): List<DeclaredReference> = declaredReferenceList

/**
 * The walk itself, called once per model from [PreparedModel.declaredReferenceByDeclaration].
 *
 * Separate from [declaredReferences] so the memoized property has something to compute and every caller keeps
 * the name it already used — the hoisting is invisible to them, which is the point.
 */
internal fun PreparedModel.computeDeclaredReferences(): Map<DeclarationKey, DeclaredReference> = declarationsByKey.mapNotNull { (key, element) ->
    val declared = DECLARED_BY_KIND[element.kind] ?: return@mapNotNull null
    val relative = element.body
        ?.get(declared.first)?.let { (it as? JsonPrimitive)?.contentOrNull }
        ?: return@mapNotNull null
    // No rehosting here, unlike a condition reference: `errorEntityRelPath` and `computedFieldRelPath` are
    // strictly RELATIVE — an absolute spelling is "illegal relPath" at conversion and the model never builds
    // (measured 2026-09-12) — and a relative path is move-invariant under mounting, because the provider's
    // root group is REPLACED by the mount group rather than added above it.
    key to DeclaredReference(element.path, declared.first, declared.second,
        resolveRelative(element.path, relative), relative)
}.toMap()

private val DECLARED_BY_KIND = mapOf(
    "Rule" to ("errorEntityRelPath" to ReferenceRole.ERROR_TARGET),
    "Computation" to ("computedFieldRelPath" to ReferenceRole.COMPUTED_TARGET),
)

/**
 * Resolve [relative] against the declaring element at [from] — `..` climbs one step, any other segment
 * descends. Climbing past the root yields a path nothing declares rather than a silently clamped one, so an
 * over-long relative path is reported instead of quietly naming a root element.
 */
private fun PreparedModel.resolveRelative(from: String, relative: String): Reference =
    resolveRelativeIn(from, relative) { it in resolvableByPath }

/** The same walk through the read barrier — it asks the resolvable set and reads no body. */
private fun TrackedFactBase.resolveRelative(from: String, relative: String): Reference =
    resolveRelativeIn(from, relative) { isResolvable(it) }

private inline fun resolveRelativeIn(
    from: String,
    relative: String,
    resolves: (String) -> Boolean,
): Reference {
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
        resolves(path) -> Reference.Resolved(path)
        else -> Reference.Undeclared(path)
    }
}

/**
 * Where this declaration's own target is addressed from — its [TargetAnchor], whose scope is the level it ITERATES.
 *
 * A rule's iteration is set by where its error field sits, not by where the rule is declared, and the two
 * part company exactly when the error field reaches into a repeatable group below the declaring one. A
 * reference reader keyed on the declaring group therefore asks the wrong question on such a rule and yields
 * on an operand A12 admits — measured as two silences in the differential lane
 * ([KERNEL-FINDINGS KF424](../../../../../../../../../../docs/KERNEL-FINDINGS.md#kf424)).
 *
 * Resolved per element rather than by scanning [declaredReferences], which is a whole-model walk and is
 * called from inside per-reference loops.
 *
 * @return null for a declaration that names no target, one that resolves to nothing, and one whose target is
 *   neither a field nor a group — in each case the caller falls back to the declaring group, which is what it
 *   asked before.
 */
internal fun TrackedFactBase.iterationAnchorOf(element: TrackedDeclaration): TargetAnchor? {
    val relative = declaredTargetRelPathOf(element.kind, element.body) ?: return null
    val target = (resolveRelative(element.path, relative) as? Reference.Resolved)?.path ?: return null
    return anchorOf(target)
}

/** The level this declaration's own target ITERATES — its anchor's scope ([iterationAnchorOf]). */
internal fun TrackedFactBase.iterationGroupOf(element: TrackedDeclaration): String? = iterationAnchorOf(element)?.scope

/** The relative target path a rule or computation spells, or null for any other carrier. */
private fun declaredTargetRelPathOf(kind: String, body: JsonObject?): String? {
    val declared = DECLARED_BY_KIND[kind] ?: return null
    return (body?.get(declared.first) as? JsonPrimitive)?.contentOrNull
}
