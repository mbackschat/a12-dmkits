package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.consistency.syntax.CONDITION_OPERATOR_KEYWORDS
import io.github.mbackschat.a12.dm.consistency.syntax.callsIn

/**
 * What a `Having` filter's OWN operand may be — a field, starred, with no semantic index on it.
 *
 * `CheckEntitySpecImpl.checkFilterCondition` carries the whole chain in one method, which is why this is one
 * family rather than two: the operand must be a field (`MVK_NO_GROUPS_ALLOWED`), then must carry a wildcard
 * (`MVK_WILDCARDS_REQUIRED`), then must carry no semantic index, and only then is the filter BODY judged —
 * the body being [filterWildcardFindings]' subject, so the two families partition the construct.
 *
 * **The order this family applies is MEASURED and is not the chain's written order**
 * ([KERNEL-FINDINGS KF358], `rulekit.validate.laws.FilteredOperandGateOrderLawsTest`). Source order puts the
 * kind gate first, so a group operand that ALSO carries a semantic index was predicted to draw the kind
 * class; A12 answers `MVK_WILDCARDS_REQUIRED`. Reading the chain and trusting its order would therefore have
 * named the wrong class on that shape, so the index test runs first here.
 *
 * **The star is not a variable on either defect, which the code's own name argues against.** A group operand
 * draws the kind class starred or not, and an indexed operand draws the wildcard class starred or not — in
 * the latter case with a `*` present in the text, so the semantic-index spelling consumes it.
 *
 * **The one silence that matters: a plainly UNSTARRED field operand is `MVK_NO_WILDCARD`, not
 * `MVK_WILDCARDS_REQUIRED`.** A12's iteration gate outruns this chain there. That is the shape the code's
 * name predicts and the measurement denies, and naming the wildcard-required class on it would send the
 * author to a repair for a gate that never fired.
 */
fun PreparedModel.filteredOperandKindFindings(): List<Finding> {
    val keywords = CONDITION_OPERATOR_KEYWORDS[conditionLanguage] ?: return emptyList()
    val filterKeyword = keywords[HAVING] ?: return emptyList()
    val indexKeyword = keywords[SEMANTIC_INDEX]
    return elements.flatMap { element ->
        val body = element.body ?: return@flatMap emptyList()
        val group = element.path.substringBeforeLast('/', missingDelimiterValue = "")
        conditionTexts(element.kind, body).flatMap { (member, text) ->
            callsIn(text, conditionLanguage, syntaxEntryOf(member))
                .flatMap { call -> call.operands }
                .filter { operand -> operand.clauses.any { it.keyword == filterKeyword } }
                .mapNotNull { operand ->
                    operandFinding(operand.base, operand.clauses.any { it.keyword == indexKeyword },
                        group, element, member)
                }
        }
    }
}

private const val HAVING = "Having"

private const val SEMANTIC_INDEX = "SemanticIndex"

private fun PreparedModel.operandFinding(
    base: String?,
    indexed: Boolean,
    declaringGroup: String,
    element: DeclaredElement,
    member: String,
): Finding? {
    if (indexed) {
        return finding(
            "MVK_WILDCARDS_REQUIRED",
            "in `$member`: the filtered operand carries a semantic index — a `Having` filter selects rows " +
                "of a starred list, and an indexed reference names one value instead",
            element.path,
        )
    }
    val path = base ?: return null
    val resolved = resolveEntity(declaringGroup, path.replace("*", ""), element.origin)
    if (resolved !is Reference.Resolved || !isGroupPath(resolved.path)) {
        return null
    }
    return finding(
        "MVK_NO_GROUPS_ALLOWED",
        "in `$member`: `Having` filters the group `${resolved.path}` — the filter selects rows by a FIELD " +
            "of the repetition, so name a field of that group instead",
        element.path,
    )
}

private fun PreparedModel.isGroupPath(path: String): Boolean =
    elements.any { it.path == path && it.declaredKind == DeclaredKind.GROUP }

private fun finding(code: String, explain: String, at: String): Finding =
    Finding(FindingCategory.A12_CONSTRAINT, FindingSeverity.ERROR, code, explain, at)
