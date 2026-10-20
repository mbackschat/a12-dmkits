package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.consistency.syntax.CONDITION_OPERATOR_KEYWORDS
import io.github.mbackschat.a12.dm.consistency.syntax.CallOperand
import io.github.mbackschat.a12.dm.consistency.syntax.HAVING

/**
 * `SumOfProducts`' operand PAIR, whose four laws are one kernel method each and run in a fixed order.
 *
 * The operator multiplies two parallel lists element-wise, so A12 demands that both operands' fields share
 * one OWNING group and that each reference star exactly the lowest repeatable level on its path
 * ([KERNEL-FINDINGS KF370], `rulekit.validate.laws.SumOfProductsLawsTest`). The Java builder has refused
 * these shapes since it was written; a stored model reaches none of that, which is what this family is for.
 *
 * **Four classes, and which one an offending pair draws is the whole difficulty.** They are ordered here as
 * `CheckFeldListenOpImpl.check` calls them, because three of the four cells that break two laws at once were
 * predicted wrongly from the laws alone:
 *
 * 1. the fields' owning groups differ — `MVK_DIFFERENT_GROUPS`, and it outranks everything below it;
 * 2. an operand carries a `Having` filter — `MVK_WILDCARD_AT_LOWEST_LEVEL_REQUIRED`, because
 *    `isWildcardRestricted` reads a filter as restricting EVERY level, so the lowest one fails first;
 * 3. an operand stars two levels — `MVK_WILDCARD_ONLY_AT_LOWEST_LEVEL_ALLOWED`;
 * 4. neither operand has a repeatable level at all — `MVK_REPEATABLE_LEVEL_REQUIRED`;
 * 5. an operand leaves its lowest repeatable level unstarred because the rule binds that level, by iterating
 *    it through its error field or by being declared inside it — `MVK_WILDCARD_AT_LOWEST_LEVEL_REQUIRED`
 *    again, for one unstarred operand or both ([KERNEL-FINDINGS KF370], measured 2026-10-20 by
 *    `adapter.laws.CheckerSumOfProductsBoundOperandAgreementTest`).
 *
 * The fifth gate is placed after the third and fourth only for the cells measured; a pair that also stars two
 * levels is left silent, because which of those classes A12 reaches first is not measured. A missing star on a
 * level the rule does NOT bind is answered by the reference reader with `MVK_NO_WILDCARD` before this
 * operator's own gates run.
 *
 * **Silent unless the reference reader is demonstrably silent.** Each operand must resolve to a FIELD, star
 * no level that does not repeat, and star every repeatable level on its path that the declaring group does
 * not bind, or A12's answer is `MVK_NO_WILDCARD` / `MVK_INVALID_WILDCARD` / `MVK_INVALID_ENTITY`, or
 * containment, and belongs to another family. The field may lie outside the declaring group, and a star on a
 * level the group binds is no reader error: the gates count it ([KERNEL-FINDINGS KF470],
 * `rulekit.validate.laws.SumOfProductsOperandReachLawsTest`). Silent
 * too on an identical starless pair and on a starred filter, which A12 answers with `MVK_DUPLICATE_PARAM1`
 * and `MVK_NO_WILDCARDS_ALLOWED` — measured, both ahead of every gate here.
 */
internal fun TrackedFactBase.sumOfProductsOperandFindings(): List<Finding> = declarations().flatMap { element ->
    sumOfProductsContributions(element).map { it.finding }
}

internal fun TrackedFactBase.sumOfProductsContributions(
    element: TrackedDeclaration,
    execution: ConditionExecution? = conditionExecution(element),
): List<ConditionFinding> {
    val keywords = CONDITION_OPERATOR_KEYWORDS[conditionLanguage] ?: return emptyList()
    val operator = keywords[SUM_OF_PRODUCTS] ?: return emptyList()
    val filterKeyword = keywords[HAVING] ?: return emptyList()
    val group = element.path.substringBeforeLast('/', missingDelimiterValue = "")
    val targetGroup = iterationScopeOf(element.declaredReference(), group)
    return callContributions(element, execution) { member, call ->
        if (call.operator != operator) return@callContributions null
        val pair = call.operands.map { readOperand(it, group, targetGroup, element, filterKeyword) }
        if (pair.size != 2 || pair.any { it == null }) return@callContributions null
        classOf(pair[0]!!, pair[1]!!)?.let { finding(it, element, member, call.spanIn(member)) }
    }
}

private const val SUM_OF_PRODUCTS = "SumOfProducts"

/**
 * One operand reduced to what the gates read, or null when this family may not judge the call. [repeats] says
 * whether its path has a repeatable level at all, [lowestUnstarred] whether its lowest one is left unstarred.
 */
private class ParallelOperand(
    val owningGroup: String,
    val stars: Int,
    val filtered: Boolean,
    val at: String,
    val repeats: Boolean,
    val lowestUnstarred: Boolean,
)

/**
 * Reads one operand, returning null on every shape whose verdict is another gate's.
 *
 * An unbound repeatable level left unstarred is read one row at a time, which the reference reader answers
 * with `MVK_NO_WILDCARD`, or containment does where the target lies outside the declaring group, ahead of
 * this operator. A level at or above the declaring group is bound by the rule's own context, and so is one on
 * the path of the group the error field iterates ([targetGroup], null where that reading is not this
 * declaration's to make), so either may go unstarred — the fixture that made the first concrete has a
 * REPEATABLE root group, where demanding its star refuses a legal pair — and a star on it is counted like any
 * other.
 */
private fun TrackedFactBase.readOperand(
    operand: CallOperand,
    declaringGroup: String,
    targetGroup: String?,
    element: TrackedDeclaration,
    filterKeyword: String,
): ParallelOperand? {
    val written = operand.base ?: return null
    val filters = operand.clauses.filter { it.keyword == filterKeyword }
    if (filters.size != operand.clauses.size || filters.any { it.text.contains('*') }) {
        return null
    }
    val resolved = resolveEntity(declaringGroup, written.replace("*", ""), element.origin)
        as? Reference.Resolved ?: return null
    val field = declarationAt(resolved.path, DeclaredKind.FIELD)
        ?: return null
    // A star on a level that does not repeat is `MVK_INVALID_WILDCARD`, which A12 answers before comparing
    // the pair (`rulekit.validate.laws.SumOfProductsLawsTest`).
    val starred = starredLevelsOf(written, declaringGroup, element) ?: return null
    val levels = iteratedLevels(field.path)
    if (levels.any { it !in starred && !declaringGroup.isAtOrBelow(it) && targetGroup?.isAtOrBelow(it) != true }) {
        return null
    }
    return ParallelOperand(
        field.path.substringBeforeLast('/'),
        starred.size,
        filters.isNotEmpty(),
        field.path,
        repeats = levels.isNotEmpty(),
        lowestUnstarred = levels.lastOrNull()?.let { it !in starred } == true,
    )
}

/** A12's class for this pair, in its own gate order, or null when the pair is legal. */
private fun classOf(left: ParallelOperand, right: ParallelOperand): String? = when {
    left.owningGroup != right.owningGroup -> "MVK_DIFFERENT_GROUPS"
    left.filtered || right.filtered -> "MVK_WILDCARD_AT_LOWEST_LEVEL_REQUIRED"
    (left.stars > 1 || right.stars > 1) && (left.lowestUnstarred || right.lowestUnstarred) -> null
    left.stars > 1 || right.stars > 1 -> "MVK_WILDCARD_ONLY_AT_LOWEST_LEVEL_ALLOWED"
    // An identical starless pair is A12's `MVK_DUPLICATE_PARAM1`, which runs ahead of these gates.
    left.stars == 0 && right.stars == 0 && left.at == right.at -> null
    !left.repeats && !right.repeats -> "MVK_REPEATABLE_LEVEL_REQUIRED"
    left.lowestUnstarred || right.lowestUnstarred -> "MVK_WILDCARD_AT_LOWEST_LEVEL_REQUIRED"
    else -> null
}

private fun finding(code: String, element: TrackedDeclaration, member: String, span: TextSpan): Finding = Finding(
    FindingCategory.A12_CONSTRAINT,
    FindingSeverity.ERROR,
    code,
    "in `$member`: `$SUM_OF_PRODUCTS` multiplies two parallel repetition lists, so both operands must name " +
        "fields of ONE group and each must star that group's lowest repeatable level — " +
        ADVICE.getValue(code),
    element.path,
    span,
)

/** What the author has to change, which differs per class though the shape is one pair. */
private val ADVICE = mapOf(
    "MVK_DIFFERENT_GROUPS" to "these two name fields of different groups",
    "MVK_WILDCARD_AT_LOWEST_LEVEL_REQUIRED" to
        "one of them leaves the lowest repeatable level without an unrestricted star, either unstarred or " +
        "restricted by a `$HAVING` filter, so star that level on both",
    "MVK_WILDCARD_ONLY_AT_LOWEST_LEVEL_ALLOWED" to "one of them stars more than the lowest level",
    "MVK_REPEATABLE_LEVEL_REQUIRED" to "neither of them has a repeatable level to iterate",
)
