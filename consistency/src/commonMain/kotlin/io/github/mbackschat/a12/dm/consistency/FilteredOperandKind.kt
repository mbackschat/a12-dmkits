package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.consistency.syntax.CONDITION_OPERATOR_KEYWORDS
import io.github.mbackschat.a12.dm.consistency.syntax.CallOperand
import io.github.mbackschat.a12.dm.consistency.syntax.HAVING
import io.github.mbackschat.a12.dm.consistency.syntax.OperandClause
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
 * **Which spec a filter belongs to is the grammar's, and it is not always the operand.** A12's semantic-index
 * key is an `entitySpec` of its own, which takes a `Having`, so a filter written after an entity key filters
 * the KEY and the chain judges the key — a `Having` after `For Reference` refuses the unstarred `Reference`,
 * and a group key draws the kind class. A string key is no spec and takes no filter, so a
 * `Having` after one filters the indexed operand, where the index gate on a starred operand is raised first
 * and an unstarred one draws this chain's wildcard class ([KERNEL-FINDINGS KF358],
 * `rulekit.validate.laws.FilteredOperandGateOrderLawsTest`, measured 2026-09-25). The chain then runs in its
 * written order: kind, then the wildcard.
 *
 * **The star is not a variable on the kind defect, which the code's own name argues against.** A group
 * operand draws the kind class starred or not.
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
                .flatMap { operand ->
                    operand.clauses.filter { it.keyword == filterKeyword }.map { filter ->
                        filteredSpecOf(operand, filter, indexKeyword, member)
                    }
                }
                .mapNotNull { spec ->
                    specFinding(spec, group, iterationScopeOf(targets[element.path], group), element, member)
                }
        }
    }
}

/**
 * The spec one `Having` filters: the path it names, whether a semantic index of its own sits between it and
 * the filter, whether it is an index KEY, and the characters of its production.
 *
 * `checkFilterCondition` is `checkEntitySpec`'s, raised once the whole spec, filter included, is read; a
 * key's spec opens at the key, so its span nests inside the index it keys and report assembly places the
 * key's class first where the two end together.
 */
private class FilteredSpec(val path: String?, val indexed: Boolean, val key: Boolean, val span: TextSpan)

private fun filteredSpecOf(operand: CallOperand, filter: OperandClause, indexKeyword: String?, member: String):
    FilteredSpec {
    val key = filter.filtersKey
    val owned = operand.clauses.subList(key?.let { operand.clauses.indexOf(it) + 1 } ?: 0,
        operand.clauses.indexOf(filter))
    val indexed = owned.any { it.keyword == indexKeyword }
    return if (key == null) {
        FilteredSpec(operand.base, indexed, key = false, operand.spanIn(member))
    } else {
        FilteredSpec(key.path, indexed, key = true, TextSpan(member, key.at, filter.at + filter.text.length))
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

private fun TrackedFactBase.specFinding(
    spec: FilteredSpec,
    declaringGroup: String,
    targetGroup: String?,
    element: TrackedDeclaration,
    member: String,
): Finding? {
    val path = spec.path ?: return null
    val resolved = resolveEntity(declaringGroup, path.replace("*", ""), element.origin)
    if (resolved !is Reference.Resolved) {
        return null
    }
    // Filtered after its own string key, a spec is measured only as an unstarred FIELD: a starred one draws
    // the index gate first, and a group is a cell no row covers.
    if (spec.indexed && ('*' in path || !isFieldPath(resolved.path))) {
        return null
    }
    val whose = if (spec.key) "the index key `$path` — a `Having` after `For` filters the key" else "`$path`"
    if (isGroupPath(resolved.path)) {
        return finding(
            "MVK_NO_GROUPS_ALLOWED",
            "in `$member`: `Having` filters $whose, which is the group `${resolved.path}` — the filter " +
                "selects rows by a FIELD of the repetition, so name a field of that group instead",
            element.path,
            spec.span,
        )
    }
    if (OUTER_MARKER in path || '*' in path || !isFieldPath(resolved.path)) {
        return null
    }
    // The iteration gate runs at the top of the same method and answers for the whole reference, so this
    // gate is only reached where that walk has nothing to say — the condition measured on all three
    // iteration carriers rather than read off the chain's written order. An index addresses one row, so an
    // indexed spec leaves that walk nothing to say (measured on the string-key row).
    if (targetGroup == null || (!spec.indexed && levelClassOf(path, declaringGroup, targetGroup, element) != null)) {
        return null
    }
    val repair = if (spec.key) {
        "a semantic index names one row and a filter selects several, so keep the one you mean"
    } else {
        "star the repetition you mean to filter"
    }
    return finding(
        "MVK_WILDCARDS_REQUIRED",
        "in `$member`: `Having` filters $whose, which reads a single value — a filter selects ROWS; $repair",
        element.path,
        spec.span,
    )
}

private fun TrackedFactBase.isGroupPath(path: String): Boolean =
    declarationAt(path, DeclaredKind.GROUP) != null

private fun TrackedFactBase.isFieldPath(path: String): Boolean =
    declarationAt(path, DeclaredKind.FIELD) != null

private fun finding(code: String, explain: String, at: String, span: TextSpan): Finding =
    Finding(FindingCategory.A12_CONSTRAINT, FindingSeverity.ERROR, code, explain, at, span)
