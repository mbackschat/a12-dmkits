package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.consistency.syntax.SyntaxSite
import io.github.mbackschat.a12.dm.consistency.syntax.normalizedPath
import io.github.mbackschat.a12.dm.consistency.syntax.CONDITION_OPERATOR_KEYWORDS
import io.github.mbackschat.a12.dm.consistency.syntax.SEMANTIC_INDEX
import io.github.mbackschat.a12.dm.consistency.syntax.semanticIndexUsesIn

/**
 * What a semantic index's KEY has to be: a field, never a group — and a read that does not ITERATE.
 *
 * `[Coverages/Limit For key]` selects the repetition whose index field carries the key's value, so a group
 * has no value to compare and A12 refuses it with `MVK_SEMANTIC_INDEX_NO_FIELD`
 * ([KERNEL-FINDINGS KF351], `rulekit.validate.laws.SemanticIndexStructureLawsTest`). It is also the FIRST
 * gate of that construct's chain: it reports ahead of the missing index field and ahead of a star on the
 * indexed path, both measured, so naming it costs no precedence analysis.
 *
 * **An ITERATING key pre-empts that class and every other gate of the construct**, and A12 RENAMES rather
 * than adds: the same read outside an index draws `MVK_NO_WILDCARD`, inside one
 * `MVK_SEMANTIC_INDEX_INVALID_ITER`, because `checkIterationAndErrorField` asks `isSemanticIndex` and picks
 * the class from the answer. So this family answers the key's two questions in A12's order — does the read
 * iterate, and only then is the key a field.
 *
 * **Addressing is relative to the error target.** A field key uses the same per-level address walk as an
 * ordinary operand: a compatible parallel index is admitted; missing, mismatched or ambiguous indexes
 * retain their own diagnostic. Only `MVK_NO_WILDCARD` is renamed to `MVK_SEMANTIC_INDEX_INVALID_ITER`
 * (`SemanticKeyAddressAgreementTest`, SPEC-2026-10-10-03). Group keys retain the measured below-target
 * boundary; parallel group-key admission has not been calibrated here.
 *
 * **And it is asked only of a key the RULE reads directly.** Inside a `Having` filter the anchoring is the
 * filtered entity rather than the error target, and A12 draws no iteration class there: two measured shapes
 * are answered by the key-kind gate and by the filter's own branch instead ([KERNEL-FINDINGS KF377]). Asking
 * it there named a class A12 does not draw, on both of them, until 2026-09-14.
 *
 * **Silent on a key that does not resolve** (`MVK_INVALID_ENTITY`, the reference family's) and on a LITERAL
 * key, which puts a string in the key position and is subject to the value gates instead.
 */
internal fun TrackedFactBase.semanticIndexKeyContributions(element: TrackedDeclaration): List<ConditionFinding> {
    val execution = conditionExecution(element) ?: return emptyList()
    val keyword = CONDITION_OPERATOR_KEYWORDS[conditionLanguage]?.get(SEMANTIC_INDEX) ?: return emptyList()
    val uses = semanticIndexUsesIn(execution.carrier.text, conditionLanguage, keyword).associateBy { it.keywordAt }
    val group = element.path.substringBeforeLast('/')
    val targetGroup = (element.declaredReference()?.target as? Reference.Resolved)?.path?.substringBeforeLast('/')
    val iteration = entityContributions(element, EntityCheck.ADDRESS, execution) { entity, span ->
        if (!entity.indexKey) return@entityContributions null
        keyIterationFinding(normalizedPath(entity.path.dropWhile { it.text == "$" }), group, targetGroup,
            element, span.member, entity.inFilter, span)
    }
    val fields = execution.recognition.sites.filterIsInstance<SyntaxSite.Completion>().mapNotNull { site ->
        if (site.kind != SyntaxSite.Kind.INDEX) return@mapNotNull null
        val use = uses[site.start] ?: return@mapNotNull null
        val key = use.keyPath ?: return@mapNotNull null
        val span = execution.carrier.semanticSpan(site.start, site.end) ?: return@mapNotNull null
        keyFieldFinding(key, group, element, span.member, span)?.let { ConditionFinding(element.key, site, it) }
    }
    return iteration + fields
}

private fun TrackedFactBase.keyIterationFinding(
    key: String,
    group: String,
    targetGroup: String?,
    element: TrackedDeclaration,
    member: String,
    insideAFilter: Boolean,
    span: TextSpan,
): Finding? {
    // A starred key is the structure family's wildcard class, and a star also ADDRESSES the level it sits
    // on, so leaving it here would ask the iteration question about a read that does not iterate.
    if ('*' in key) {
        return null
    }
    if (insideAFilter || targetGroup == null) return null
    // Containment answers first on an iterating key (SelectedContainmentAgreementTest, KF488).
    if (targetGroup != group && !targetGroup.startsWith("$group/")) return null
    val resolved = resolveEntity(group, key, element.origin) as? Reference.Resolved ?: return null
    val addressClass = if (declarationAt(resolved.path)?.declaredKind == DeclaredKind.FIELD) {
        levelClassOf(key, group, targetGroup, element)
    } else {
        iteratedBelow(resolved.path, targetGroup)?.let { "MVK_NO_WILDCARD" }
    } ?: return null
    val code = when (addressClass) {
        "MVK_NO_WILDCARD" -> "MVK_SEMANTIC_INDEX_INVALID_ITER"
        else -> addressClass
    }
    return Finding(
        FindingCategory.A12_CONSTRAINT,
        FindingSeverity.ERROR,
        code,
        "this $member keys a semantic index on `$key`, whose repetition cannot be addressed from " +
            "the error target's group `$targetGroup` — use a key in the target's own scope, or " +
            "compatible index fields for parallel repetitions",
        element.path,
        span,
    )
}

private fun TrackedFactBase.keyFieldFinding(
    key: String, group: String, element: TrackedDeclaration, member: String, span: TextSpan,
): Finding? {
    if ('*' in key) return null
    val resolved = resolveEntity(group, key, element.origin) as? Reference.Resolved ?: return null
    val named = declarationAt(resolved.path, DeclaredKind.GROUP)
        ?: return null
    if (repeatsAnywhereOnThePathTo(named.path)) {
        return null
    }
    return Finding(
        FindingCategory.A12_CONSTRAINT,
        FindingSeverity.ERROR,
        "MVK_SEMANTIC_INDEX_NO_FIELD",
        "this $member keys a semantic index on `${named.path}`, which is a group — the key is compared " +
            "against the indexed group's index field, so it has to name a field",
        element.path,
        span,
    )
}

/**
 * The outermost repeatable level [path] rides that the target's own coordinates cannot address — strictly
 * below [targetGroup] — or null where the read does not iterate or the anchoring is unknown.
 */
private fun TrackedFactBase.iteratedBelow(path: String, targetGroup: String?): String? {
    if (targetGroup == null) {
        return null
    }
    return iteratedLevels(path).firstOrNull { it.startsWith("$targetGroup/") }
}

/** Whether [path] or any group above it repeats, which is what makes a read of it iterate. */
private fun TrackedFactBase.repeatsAnywhereOnThePathTo(path: String): Boolean =
    repeatableGroups().any { it.path == path || path.startsWith("${it.path}/") }
