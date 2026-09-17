package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.consistency.syntax.CONDITION_OPERATOR_KEYWORDS
import io.github.mbackschat.a12.dm.consistency.syntax.callsIn
import io.github.mbackschat.a12.dm.consistency.syntax.semanticIndexKeysIn

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
 * **The gate turns on the rule's ANCHORING, which is why it takes the error target's group.** A repeatable
 * level the target itself sits inside is addressed by the target's own coordinates and the read does not
 * iterate; only a level strictly BELOW that group does. Reporting without the anchoring would refuse a key
 * A12 admits, which is the one direction forbidden here.
 *
 * **Silent on every other geometry**, and deliberately: a level PARALLEL to the target's chain draws one of
 * four index-keyed classes outside an index ([KF350]) and nothing has measured which name it takes inside
 * one, so this reports only the geometry both halves were measured on — a level strictly below the target's
 * group ([KF351]).
 *
 * **And it is asked only of a key the RULE reads directly.** Inside a `Having` filter the anchoring is the
 * filtered entity rather than the error target, and A12 draws no iteration class there: two measured shapes
 * are answered by the key-kind gate and by the filter's own branch instead ([KERNEL-FINDINGS KF377]). Asking
 * it there named a class A12 does not draw, on both of them, until 2026-09-14.
 *
 * **Silent on a key that does not resolve** (`MVK_INVALID_ENTITY`, the reference family's) and on a LITERAL
 * key, which puts a string in the key position and is subject to the value gates instead.
 */
internal fun TrackedFactBase.semanticIndexKeyFindings(): List<Finding> {
    val keywords = CONDITION_OPERATOR_KEYWORDS[conditionLanguage] ?: return emptyList()
    val keyword = keywords[SEMANTIC_INDEX] ?: return emptyList()
    val filterKeyword = keywords[HAVING]
    val targets = declaredReferences().associateBy { it.declaredAt }
    return declarations().flatMap { element ->
        val body = element.body ?: return@flatMap emptyList()
        val group = element.path.substringBeforeLast('/', missingDelimiterValue = "")
        val targetGroup = (targets[element.path]?.target as? Reference.Resolved)?.path
            ?.substringBeforeLast('/', missingDelimiterValue = "")
        conditionTexts(element.kind, body).flatMap { (member, text) ->
            val filtered = keysInsideAFilter(text, member, filterKeyword, keyword)
            semanticIndexKeysIn(text, conditionLanguage, keyword)
                .distinct()
                .mapNotNull { key ->
                    keyFinding(key, group, targetGroup, element, member, key in filtered)
                }
        }
    }
}

/**
 * The index keys written inside a `Having` filter, whose ANCHORING is the filtered entity rather than the
 * rule's error target.
 *
 * A12 draws no iteration class for such a key: two measured shapes inside a filter are answered by the
 * key-kind gate and by the filter's own branch instead ([KERNEL-FINDINGS KF377],
 * `rulekit.validate.laws.SemanticIndexInFilterLawsTest`), where outside one the iteration class pre-empts
 * both. So the gate below is asked only of a key the rule reads directly.
 */
private fun TrackedFactBase.keysInsideAFilter(
    text: String,
    member: String,
    filterKeyword: String?,
    indexKeyword: String,
): Set<String> {
    if (filterKeyword == null) {
        return emptySet()
    }
    return callsIn(text, conditionLanguage, syntaxEntryOf(member))
        .flatMap { it.operands }
        .flatMap { operand -> operand.clauses.filter { it.keyword == filterKeyword } }
        .flatMapTo(mutableSetOf()) { semanticIndexKeysIn(it.text, conditionLanguage, indexKeyword) }
}

private const val SEMANTIC_INDEX = "SemanticIndex"

private const val HAVING = "Having"

/** The finding one key draws, in A12's order, or null for every shape it answers with another class. */
private fun TrackedFactBase.keyFinding(
    key: String,
    group: String,
    targetGroup: String?,
    element: TrackedDeclaration,
    member: String,
    insideAFilter: Boolean,
): Finding? {
    // A starred key is the structure family's wildcard class, and a star also ADDRESSES the level it sits
    // on, so leaving it here would ask the iteration question about a read that does not iterate.
    if ('*' in key) {
        return null
    }
    val resolved = resolveEntity(group, key, element.origin) as? Reference.Resolved ?: return null
    iteratedBelow(resolved.path, targetGroup.takeUnless { insideAFilter })?.let { level ->
        return Finding(
            FindingCategory.A12_CONSTRAINT,
            FindingSeverity.ERROR,
            "MVK_SEMANTIC_INDEX_INVALID_ITER",
            "this $member keys a semantic index on `$key`, which is read from inside the repeatable " +
                "`$level` while the error target sits outside it — the key names one value, so the read " +
                "may not iterate; star the level on the target's side, or key on a field that does not repeat",
            element.path,
        )
    }
    val named = declarations().firstOrNull { it.path == resolved.path && it.declaredKind == DeclaredKind.GROUP }
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
    declarations().any { it.declaredKind == DeclaredKind.GROUP && it.isRepeatable &&
        (it.path == path || path.startsWith("${it.path}/")) }
