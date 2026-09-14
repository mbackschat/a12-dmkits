package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.consistency.syntax.CONDITION_OPERATOR_KEYWORDS
import io.github.mbackschat.a12.dm.consistency.syntax.CallOperand
import io.github.mbackschat.a12.dm.consistency.syntax.ConditionCall
import io.github.mbackschat.a12.dm.consistency.syntax.callsIn

/**
 * What the six rounding operators demand of the thing they round and of the digit count they round to.
 *
 * They are TWO constructs sharing one digits gate, and which one an operator is decides what its operand may
 * be ([KERNEL-FINDINGS KF393], `rulekit.validate.laws.RoundingOperandLawsTest`):
 *
 * | the spelling | the operand | the gates it meets, in order |
 * |---|---|---|
 * | `RoundUpValue`, `RoundDownValue`, `RoundAccountingValue` | an entity SPEC | `MVK_NO_GROUPS_ALLOWED`, `MVK_NO_WILDCARDS_ALLOWED`, `MVK_CATEGORY_NOT_ALLOWED`, `MVK_NO_NUMBER` |
 * | `RoundUp`, `RoundDown`, `RoundAccounting` | an OPERATION | `MVK_NO_CONST`, `MVK_NO_NUMBER` |
 *
 * **Then both ask the same question of the digits**, `MVK_INVALID_DIGIT_FOR_ROUNDING`: a non-negative integer
 * no greater than 14, the ceiling the kernel source dates to `A12K-3691`.
 *
 * **The digits gate is asked LAST on both constructs**, so an out-of-range count is invisible behind an
 * operand the construct refuses for its own reason — measured on both, and the reason this family walks the
 * gates in order rather than reporting whichever it finds.
 *
 * **Neither refusal set is derivable from the other.** The bare spelling refuses a CONSTANT, which the field
 * spelling has no way to express; the field spelling refuses a group, a wildcard and a category, none of
 * which is an operation. A consumer treating the six as one operator is wrong on both halves.
 *
 * **Silent on an operand this reader cannot reduce to one entity spec** — an arithmetic expression, a nested
 * call, a category reference — which is why `MVK_CATEGORY_NOT_ALLOWED` appears in the table above and is
 * never reported: A12 refuses it and this reader cannot see it, so the silence is an under-report rather
 * than a disagreement.
 */
fun PreparedModel.roundingOperandFindings(): List<Finding> {
    val keywords = CONDITION_OPERATOR_KEYWORDS[conditionLanguage] ?: return emptyList()
    val carriers = OPERAND_KIND_OF.mapNotNull { (id, kind) -> keywords[id]?.let { it to kind } }.toMap()
    if (carriers.isEmpty()) {
        return emptyList()
    }
    return elements.flatMap { element ->
        val body = element.body ?: return@flatMap emptyList()
        val declaringGroup = element.path.substringBeforeLast('/', missingDelimiterValue = "")
        conditionTexts(element.kind, body).flatMap { (member, text) ->
            callsIn(text, conditionLanguage, syntaxEntryOf(member))
                .mapNotNull { call ->
                    val kind = carriers[call.operator] ?: return@mapNotNull null
                    roundingDefect(call, kind, declaringGroup, element)
                        ?.let { defect -> finding(defect, call, element, member) }
                }
        }
    }
}

/** Which of the two constructs an operator belongs to — the grammar rule its name selects. */
private enum class RoundedOperand { ENTITY_SPEC, OPERATION }

private val OPERAND_KIND_OF = mapOf(
    "RoundUpValue" to RoundedOperand.ENTITY_SPEC,
    "RoundDownValue" to RoundedOperand.ENTITY_SPEC,
    "RoundAccountingValue" to RoundedOperand.ENTITY_SPEC,
    "RoundUp" to RoundedOperand.OPERATION,
    "RoundDown" to RoundedOperand.OPERATION,
    "RoundAccounting" to RoundedOperand.OPERATION,
)

/** A12's ceiling, raised to 14 by `A12K-3691` and read straight off `CheckOpUtils`. */
private const val MOST_ROUNDED_DIGITS = 14

/** One gate's answer: the class, and the whole clause that follows the member in the message. */
private class RoundingDefect(val code: String, val detail: String)

/** The first gate this call fails, in A12's own order, or null where none fails and where it may not judge. */
private fun PreparedModel.roundingDefect(
    call: ConditionCall,
    kind: RoundedOperand,
    declaringGroup: String,
    element: DeclaredElement,
): RoundingDefect? {
    val rounded = call.operands.firstOrNull() ?: return null
    val operandDefect = when (kind) {
        RoundedOperand.ENTITY_SPEC -> entitySpecDefect(rounded, declaringGroup, element)
        RoundedOperand.OPERATION -> operationDefect(rounded, declaringGroup, element)
    }
    return operandDefect ?: digitsDefect(call)
}

/** The field construct's three shape gates and its type gate, in the order `CheckRundenOpImpl` calls them. */
private fun PreparedModel.entitySpecDefect(
    rounded: CallOperand,
    declaringGroup: String,
    element: DeclaredElement,
): RoundingDefect? {
    val written = rounded.path ?: return null
    val target = addressedTargetOf(written, declaringGroup, element) ?: return null
    val declared = elements.firstOrNull { it.path == target } ?: return null
    if (declared.declaredKind == DeclaredKind.GROUP) {
        return RoundingDefect("MVK_NO_GROUPS_ALLOWED", "rounds `$target`, which is a group — round one field of it")
    }
    if (declared.declaredKind != DeclaredKind.FIELD) {
        return null
    }
    if ('*' in written) {
        return RoundingDefect(
            "MVK_NO_WILDCARDS_ALLOWED",
            "rounds `$target` through a repetition star, and rounding takes one addressed value",
        )
    }
    return if (declared.declaredFieldType() in PLAINLY_NON_NUMBER_TYPES) {
        RoundingDefect(
            "MVK_NO_NUMBER",
            "rounds `$target`, declared `${declared.declaredFieldType()}` — only a number can be rounded",
        )
    } else {
        null
    }
}

/** The operation construct's two gates, in the order `CheckRundenOperOpImpl` calls them. */
private fun PreparedModel.operationDefect(
    rounded: CallOperand,
    declaringGroup: String,
    element: DeclaredElement,
): RoundingDefect? {
    if (rounded.constant) {
        return RoundingDefect(
            "MVK_NO_CONST",
            "rounds the constant `${rounded.text.trim()}`, and this spelling rounds a computed value — " +
                "round the value it stands for, or reach for the `…Value` spelling over a field",
        )
    }
    // A12 types the whole operation; this reader types only a bare field reference, so an expression is left
    // alone rather than typed from its most visible operand.
    val field = bareReferenceField(rounded.text, declaringGroup, element) ?: return null
    return if (field.declaredFieldType() in PLAINLY_NON_NUMBER_TYPES) {
        RoundingDefect(
            "MVK_NO_NUMBER",
            "rounds `${field.path}`, declared `${field.declaredFieldType()}` — only a number can be rounded",
        )
    } else {
        null
    }
}

/**
 * The shared digits gate, asked of the second argument only where it is written as plain digits.
 *
 * A12 refuses anything `StringUtils.isNumeric` rejects, which is every other spelling — but the grammar's own
 * token may refuse those first, and that is not measured, so this reader answers only for the counts it can
 * read. Absent digits are A12's own default of zero.
 */
private fun digitsDefect(call: ConditionCall): RoundingDefect? {
    val written = call.operands.getOrNull(1)?.text?.trim() ?: return null
    if (written.isEmpty() || !written.all { it.isDigit() }) {
        return null
    }
    // Three digits or more is above the ceiling whatever it says, which keeps the parse off a long literal.
    val tooMany = written.length > 2 || written.toInt() > MOST_ROUNDED_DIGITS
    return if (tooMany) {
        RoundingDefect(
            "MVK_INVALID_DIGIT_FOR_ROUNDING",
            "rounds to `$written` decimal places, more than the $MOST_ROUNDED_DIGITS A12 admits",
        )
    } else {
        null
    }
}

private fun finding(
    defect: RoundingDefect,
    call: ConditionCall,
    element: DeclaredElement,
    member: String,
): Finding = Finding(
    FindingCategory.A12_CONSTRAINT,
    FindingSeverity.ERROR,
    defect.code,
    "in `$member`: `${call.operator}` ${defect.detail}",
    element.path,
)
