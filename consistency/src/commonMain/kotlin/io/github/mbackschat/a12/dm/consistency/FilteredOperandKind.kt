package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.consistency.syntax.OUTER_MARKER
import io.github.mbackschat.a12.dm.consistency.syntax.SyntaxSite
import io.github.mbackschat.a12.dm.consistency.syntax.normalizedPath

/**
 * What a `Having` filter's OWN operand may be — a field, starred, with no semantic index on it.
 *
 * The operand must be a field (`MVK_NO_GROUPS_ALLOWED`), then must carry a wildcard (`MVK_WILDCARDS_REQUIRED`),
 * then must carry no semantic index (`MVK_SEMANTIC_INDEX_NOT_ALLOWED`), and the filter BODY is [fieldValueReadOperandFindings]' subject, so the two
 * families partition the construct. **Both classes are raised once the whole spec, body included, is read**,
 * by `CheckEntitySpecImpl.checkFilterCondition`, the kind first: a class the body draws is raised ahead of
 * either, and so is whatever the iteration walk and the `$` check answer for the spec itself
 * (`rulekit.validate.laws.FilterBodyComparisonLawsTest.whereTheFilterChainRaisesAroundItsBody`). Kernel 30.8.1
 * asked the kind as the parser read the `Having` keyword, ahead of the body; 31.1.1 moved it, and admits a
 * filtered group only as the iteration entity of `SumOfTerms` ([KERNEL-FINDINGS KF656]).
 *
 * **Which spec a filter belongs to is the grammar's, and it is not always the operand.** A12's semantic-index
 * key is an `entitySpec` of its own, which takes a `Having`, so a filter written after an entity key filters
 * the KEY and the chain judges the key — a `Having` after `For Reference` refuses the unstarred `Reference`,
 * and a group key draws the kind class. A string key is no spec and takes no filter, so a
 * `Having` after one filters the indexed operand, where the index gate on a starred operand is raised first
 * and an unstarred one draws this chain's wildcard class ([KERNEL-FINDINGS KF358],
 * `rulekit.validate.laws.FilteredOperandGateOrderLawsTest`, measured 2026-09-25).
 *
 * **The star is not a variable on the kind defect, which the code's own name argues against.** A group
 * operand draws the kind class starred or not, wherever the iteration walk lets the chain reach it.
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
internal fun TrackedFactBase.filteredOperandKindFindings(): List<Finding> = declarations().flatMap {
    filteredOperandContributions(it).map { contribution -> contribution.finding }
}

internal fun TrackedFactBase.filteredOperandContributions(
    element: TrackedDeclaration,
    execution: ConditionExecution? = conditionExecution(element),
): List<ConditionFinding> {
    if (execution == null) return emptyList()
    val group = element.path.substringBeforeLast('/')
    val anchor = iterationScopeOf(element.declaredReference(), group)
    // A `SumOfTerms` iteration entity is no filtered OPERAND: A12 admits a filtered group there (KF656), and
    // what else it asks of that entity is unmeasured, so the chain says nothing about it rather than guess.
    return execution.recognition.sites.filterIsInstance<SyntaxSite.FilterEntry>().filterNot { it.iteration }.mapNotNull { entry ->
        val entity = execution.entitiesByStart[entry.start] ?: return@mapNotNull null
        val span = execution.carrier.semanticSpan(entity.start, entity.end) ?: return@mapNotNull null
        val spec = FilteredSpec(normalizedPath(entry.path), entry.indexed, entry.indexKey, span)
        specFinding(spec, group, anchor, element)?.let {
            ConditionFinding(element.key, EntityCheckSite(entity, EntityCheck.FILTER), it)
        }
    }
}

/**
 * The spec one `Having` filters: the path it names, whether a semantic index of its own sits between it and
 * the filter, whether it is an index KEY, and the characters of its production.
 *
 * [span] is `checkEntitySpec`'s, raised once the whole spec, filter included, is read; a key's spec opens at the
 * key, so its span nests inside the index it keys and report assembly places the key's class first where the two
 * end together.
 */
private class FilteredSpec(
    val path: String?,
    val indexed: Boolean,
    val key: Boolean,
    val span: TextSpan,
)

/**
 * Where the declaration's target is addressed from ([TargetAnchor]), when this reader may use it.
 *
 * Null wherever the iteration walk is not this declaration's to run: a target that is neither a field nor a
 * group, and one
 * outside the declaring group, where `MVK_ERROR_FIELD_NOT_IN_RULEGROUP` answers at the top of the same
 * method and no later gate is reached. Null makes the wildcard gate below silent, which is the safe
 * direction: it can only under-report a class A12 states.
 */
internal fun TrackedFactBase.iterationScopeOf(declared: DeclaredReference?, declaringGroup: String): TargetAnchor? {
    val target = (declared?.target as? Reference.Resolved)?.path ?: return null
    if (!(target == declaringGroup || target.startsWith("$declaringGroup/"))) {
        return null
    }
    return anchorOf(target)
}

private fun TrackedFactBase.specFinding(
    spec: FilteredSpec,
    declaringGroup: String,
    anchor: TargetAnchor?,
    element: TrackedDeclaration,
): Finding? {
    val member = spec.span.member
    val path = spec.path ?: return null
    val resolved = resolveEntity(declaringGroup, path.replace("*", ""), element.origin)
    if (resolved !is Reference.Resolved) {
        return null
    }
    val whose = if (spec.key) "the index key `$path` — a `Having` after `For` filters the key" else "`$path`"
    if (isGroupPath(resolved.path)) {
        if (OUTER_MARKER in path || !iterationWalkSilent(spec, path, declaringGroup, anchor, element)) {
            return null
        }
        return finding(
            "MVK_NO_GROUPS_ALLOWED",
            "in `$member`: `Having` filters $whose, which is the group `${resolved.path}` — the filter " +
                "selects rows by a FIELD of the repetition, so name a field of that group instead",
            element.path,
            spec.span,
        )
    }
    // A starred spec filtered after its own string key meets `checkNoSemanticIndex`, the chain's third gate, once
    // the whole spec is read. A star on or above the keyed level is refused earlier, at the key, by the index's
    // own gate, and that earlier-closing class is the one report assembly keeps; a star below it is admitted by
    // the index (KF351), so the filter's gate is what answers, whatever the body reads (KERNEL-FINDINGS KF568).
    if (spec.indexed && '*' in path && isFieldPath(resolved.path)) {
        if (spec.key) return null
        return finding(
            "MVK_SEMANTIC_INDEX_NOT_ALLOWED",
            "in `$member`: `Having` filters `$path`, which a semantic index already narrows to one row of a " +
                "level above its star — A12 takes no index on a filtered operand; filter on the key's field " +
                "in the body instead, or drop the filter",
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
    if (!iterationWalkSilent(spec, path, declaringGroup, anchor, element)) return null
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

/** Whether the iteration walk at the top of `checkEntitySpec` lets the filter chain be reached for [spec]. */
private fun TrackedFactBase.iterationWalkSilent(
    spec: FilteredSpec,
    path: String,
    declaringGroup: String,
    anchor: TargetAnchor?,
    element: TrackedDeclaration,
): Boolean = anchor != null && (spec.indexed || levelClassOf(path, declaringGroup, anchor, element) == null)

private fun TrackedFactBase.isGroupPath(path: String): Boolean =
    declarationAt(path, DeclaredKind.GROUP) != null

private fun TrackedFactBase.isFieldPath(path: String): Boolean =
    declarationAt(path, DeclaredKind.FIELD) != null

private fun finding(code: String, explain: String, at: String, span: TextSpan): Finding =
    Finding(FindingCategory.A12_CONSTRAINT, FindingSeverity.ERROR, code, explain, at, span)
