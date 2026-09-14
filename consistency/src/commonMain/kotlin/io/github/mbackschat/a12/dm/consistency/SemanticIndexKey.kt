package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.consistency.syntax.CONDITION_OPERATOR_KEYWORDS
import io.github.mbackschat.a12.dm.consistency.syntax.semanticIndexKeysIn

/**
 * What a semantic index's KEY has to be: a field, never a group.
 *
 * `[Coverages/Limit For key]` selects the repetition whose index field carries the key's value, so a group
 * has no value to compare and A12 refuses it with `MVK_SEMANTIC_INDEX_NO_FIELD`
 * ([KERNEL-FINDINGS KF351], `rulekit.validate.laws.SemanticIndexStructureLawsTest`). It is also the FIRST
 * gate of that construct's chain: it reports ahead of the missing index field and ahead of a star on the
 * indexed path, both measured, so naming it costs no precedence analysis.
 *
 * **The one condition that is not about the key at all, and it is what bounds this family.** A key that
 * ITERATES — a repeatable group, or a field read from inside one while the error target sits outside — is
 * pre-empted by A12's ordinary iteration gate, which reports `MVK_SEMANTIC_INDEX_INVALID_ITER` rather than
 * the key-kind class. That gate is the same one answering `MVK_NO_WILDCARD` outside an index ([KF350]); it
 * turns on the rule's anchoring, which this family cannot see, so it reports only where nothing on the key's
 * path repeats and no reading of the rule could make the read iterate.
 *
 * **Silent on a key that does not resolve** (`MVK_INVALID_ENTITY`, the reference family's) and on a LITERAL
 * key, which puts a string in the key position and is subject to the value gates instead.
 */
fun PreparedModel.semanticIndexKeyFindings(): List<Finding> {
    val keywords = CONDITION_OPERATOR_KEYWORDS[conditionLanguage] ?: return emptyList()
    val keyword = keywords[SEMANTIC_INDEX] ?: return emptyList()
    return elements.flatMap { element ->
        val body = element.body ?: return@flatMap emptyList()
        val group = element.path.substringBeforeLast('/', missingDelimiterValue = "")
        conditionTexts(element.kind, body).flatMap { (member, text) ->
            semanticIndexKeysIn(text, conditionLanguage, keyword)
                .distinct()
                .mapNotNull { key -> keyFinding(key, group, element, member) }
        }
    }
}

private const val SEMANTIC_INDEX = "SemanticIndex"

/** The finding one key draws, or null for every shape A12 answers with another class. */
private fun PreparedModel.keyFinding(
    key: String,
    group: String,
    element: DeclaredElement,
    member: String,
): Finding? {
    val resolved = resolveEntity(group, key, element.origin) as? Reference.Resolved ?: return null
    val target = elements.firstOrNull { it.path == resolved.path && it.declaredKind == DeclaredKind.GROUP }
        ?: return null
    if (repeatsAnywhereOnThePathTo(target.path)) {
        return null
    }
    return Finding(
        FindingCategory.A12_CONSTRAINT,
        FindingSeverity.ERROR,
        "MVK_SEMANTIC_INDEX_NO_FIELD",
        "this $member keys a semantic index on `${target.path}`, which is a group — the key is compared " +
            "against the indexed group's index field, so it has to name a field",
        element.path,
    )
}

/** Whether [path] or any group above it repeats, which is what makes a read of it iterate. */
private fun PreparedModel.repeatsAnywhereOnThePathTo(path: String): Boolean =
    elements.any { it.declaredKind == DeclaredKind.GROUP && it.isRepeatable &&
        (it.path == path || path.startsWith("${it.path}/")) }
