package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.consistency.syntax.CONDITION_OPERATOR_KEYWORDS

/**
 * The per-call CONSTANT budget of the numeric operand-list `Min` and `Max`.
 *
 * Each call independently admits at most ONE immediate-or-bracketed literal; a second draws
 * `MVK_TOO_MANY_CONST_FOR_CALC_OP` ([KERNEL-FINDINGS §5], `rulekit.validate.laws.OpListMathConstantLawsTest`).
 * A12 counts them in `CheckFeldListenOperOpImpl.checkMaxOneConstant`, which runs BEFORE the operand-type
 * gates of the same call — so this class is what a two-constant call draws even when its operands would also
 * fail the sortability check.
 *
 * **The budget is per CALL and never flattened**, which is the whole subtlety: `Min(Min([A], 1), 2)` is
 * admitted because the inner literal belongs to the inner call, while the flattened `Min([A], 1, 2)` is
 * refused. A reader that walked the token stream counting literals would refuse both, so this counts the
 * operands of each call in isolation, exactly as A12 does.
 *
 * **A literal inside arithmetic does not count either** — `[A] + 1` is an arithmetic operand, not a
 * constant — and that falls out of the same rule: an operand counts only when the WHOLE of it is one
 * literal, with any `{…}` grouping unwrapped.
 */
internal fun TrackedFactBase.operandListConstantFindings(element: TrackedDeclaration): List<Finding> =
    operandListConstantContributions(element).map { it.finding }

internal fun TrackedFactBase.operandListConstantContributions(
    element: TrackedDeclaration,
    execution: ConditionExecution? = conditionExecution(element),
): List<ConditionFinding> {
    val keywords = CONDITION_OPERATOR_KEYWORDS[conditionLanguage] ?: return emptyList()
    val carriers = OPERAND_LIST_MATH.mapNotNull { keywords[it] }.toSet()
    if (carriers.isEmpty()) {
        return emptyList()
    }
    return callContributions(element, execution) { member, call ->
        if (call.operator in carriers && call.operands.count { it.constant } > 1)
            budgetFinding(call.operator, element, member, call.spanIn(member)) else null
    }
}

/** The two operand-list selectors, named by their catalog ids; the aggregates `MinValue`/`MaxValue` differ. */
private val OPERAND_LIST_MATH = listOf("Min", "Max")

private fun budgetFinding(operator: String, element: TrackedDeclaration, member: String, span: TextSpan): Finding = Finding(
    FindingCategory.A12_CONSTRAINT,
    FindingSeverity.ERROR,
    "MVK_TOO_MANY_CONST_FOR_CALC_OP",
    "in `$member`: one `$operator` call takes at most one literal and this one lists several — fold them " +
        "into a single literal, or nest a second `$operator` call, which carries its own budget",
    element.path,
    span,
)
