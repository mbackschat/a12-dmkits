package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.consistency.syntax.CONDITION_OPERATOR_KEYWORDS
import io.github.mbackschat.a12.dm.consistency.syntax.HAVING
import io.github.mbackschat.a12.dm.consistency.syntax.SEMANTIC_INDEX
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
 * **An unstarred FIELD operand draws this class exactly when the ITERATION gate has nothing to say.** The
 * first measurement of it was taken on a rule that does not iterate, where A12's iteration gate outruns the
 * whole chain and answers `MVK_NO_WILDCARD` — recorded then as a flat silence, because every row of that
 * grid shared a non-iterating carrier nobody had named as a variable. Moving it shows the silence is
 * conditional: on a rule whose error field sits INSIDE the repetition, and on an operand that crosses no
 * repetition at all, the iteration gate is satisfied and this gate is what reports
 * (`rulekit.validate.laws.AggregateSlotLawsTest.whichClassAnUnstarredAggregateOperandDrawsOnEachIterationCarrier`,
 * measured 2026-09-15).
 *
 * So the condition is not containment but whatever the iteration walk itself answers, which is why this asks
 * [levelClassOf] rather than re-deriving it: a level the target iterates JOINTLY with — parallel, both ends
 * keyed alike — satisfies that gate too and draws this class, and a containment test would have called that
 * cell silent. That row was measured rather than reasoned, on its own fixture, for the same reason the
 * carrier above had to be.
 */
internal fun TrackedFactBase.filteredOperandKindFindings(): List<Finding> {
    val keywords = CONDITION_OPERATOR_KEYWORDS[conditionLanguage] ?: return emptyList()
    val filterKeyword = keywords[HAVING] ?: return emptyList()
    val indexKeyword = keywords[SEMANTIC_INDEX]
    val targets = declaredReferences().associateBy { it.declaredAt }
    return declarations().flatMap { element ->
        val body = element.body ?: return@flatMap emptyList()
        val group = element.path.substringBeforeLast('/', missingDelimiterValue = "")
        conditionTexts(element.kind, body).flatMap { (member, text) ->
            callsIn(text, conditionLanguage, syntaxEntryOf(member))
                .flatMap { call -> call.operands }
                .filter { operand -> operand.clauses.any { it.keyword == filterKeyword } }
                .mapNotNull { operand ->
                    operandFinding(operand.base, operand.clauses.any { it.keyword == indexKeyword },
                        group, iterationScopeOf(targets[element.path], group), element, member)
                }
        }
    }
}

/**
 * The group whose repetitions the declaration's target is addressed by, when this reader may use it.
 *
 * Null wherever the iteration walk is not this declaration's to run: a target that is not a FIELD, and one
 * outside the declaring group, where `MVK_ERROR_FIELD_NOT_IN_RULEGROUP` answers at the top of the same
 * method and no later gate is reached. Null makes the wildcard gate below silent, which is the safe
 * direction: it can only under-report a class A12 states.
 */
private fun TrackedFactBase.iterationScopeOf(declared: DeclaredReference?, declaringGroup: String): String? {
    val target = (declared?.target as? Reference.Resolved)?.path ?: return null
    if (!(target == declaringGroup || target.startsWith("$declaringGroup/"))) {
        return null
    }
    return groupOfTargetField(target)
}

private fun TrackedFactBase.operandFinding(
    base: String?,
    indexed: Boolean,
    declaringGroup: String,
    targetGroup: String?,
    element: TrackedDeclaration,
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
    if (resolved !is Reference.Resolved) {
        return null
    }
    if (isGroupPath(resolved.path)) {
        return finding(
            "MVK_NO_GROUPS_ALLOWED",
            "in `$member`: `Having` filters the group `${resolved.path}` — the filter selects rows by a " +
                "FIELD of the repetition, so name a field of that group instead",
            element.path,
        )
    }
    if (OUTER_MARKER in path || '*' in path || !isFieldPath(resolved.path) || targetGroup == null) {
        return null
    }
    // The iteration gate runs at the top of the same method and answers for the whole reference, so this
    // gate is only reached where that walk has nothing to say — the condition measured on all three
    // iteration carriers rather than read off the chain's written order.
    if (levelClassOf(path, declaringGroup, targetGroup, element) != null) {
        return null
    }
    return finding(
        "MVK_WILDCARDS_REQUIRED",
        "in `$member`: `Having` filters `$path`, which reads a single value — a filter selects ROWS, so " +
            "star the repetition you mean to filter",
        element.path,
    )
}

private fun TrackedFactBase.isGroupPath(path: String): Boolean =
    declarationAt(path, DeclaredKind.GROUP) != null

private fun TrackedFactBase.isFieldPath(path: String): Boolean =
    declarationAt(path, DeclaredKind.FIELD) != null

private fun finding(code: String, explain: String, at: String): Finding =
    Finding(FindingCategory.A12_CONSTRAINT, FindingSeverity.ERROR, code, explain, at)
