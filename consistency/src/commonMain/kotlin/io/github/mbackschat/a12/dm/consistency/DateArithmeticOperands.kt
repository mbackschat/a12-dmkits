package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.consistency.syntax.CONDITION_OPERATOR_KEYWORDS
import io.github.mbackschat.a12.dm.consistency.syntax.ConditionCall

/**
 * The four questions a date-arithmetic operator asks of the date it shifts, and the one it asks of the amount.
 *
 * `CheckDatumAddOpImpl.check` runs them in a fixed order over one operand, so a declaration that fails two of
 * them draws the earlier class ([KERNEL-FINDINGS KF392],
 * `rulekit.validate.laws.DateArithmeticOperandLawsTest`):
 *
 * | # | the question | class |
 * |---|---|---|
 * | 1 | is the date fully known? | `MVK_INVALID_DATE_TYPE` |
 * | 2 | does its format carry the component this operator adds? | `MVK_WRONG_DATE_FORMAT_FOR_OP` |
 * | 3 | does its format carry any calendar component at all? | `MVK_INVALID_TIME` |
 * | 4 | is it a stamp under `AddMonths` or `AddYears`? | `MVK_WRONG_DATE_FORMAT_FOR_OP` |
 * | 5 | is the amount a number? | `MVK_NON_NUMERIC` |
 *
 * **`MVK_INVALID_TIME` is reachable only through the CLOCK operators**, which is what the gate ORDER settles
 * and a reading of gate 3 alone gets backwards. Gate 3 sits behind gate 2, so any format reaching it under
 * `AddDays`/`AddMonths`/`AddYears` already carries that operator's calendar component; only
 * `AddHours`/`AddMinutes`/`AddSeconds` over a pure `TimeType` arrive with none.
 *
 * **Gate 4 is not a component question**, which is why it needs its own row: `AddMonths` and `AddYears` refuse
 * a `DateTimeType` whose format carries everything they ask for — the kernel's own comment cites `A12K-620`,
 * the behaviour over a stamp never having been settled. `AddDays` and the three clock operators accept the
 * same field, and that pair is the discriminator.
 *
 * **A model-level BASE YEAR disables gate 2 for `AddYears` alone**, exactly as it does for the aggregates'
 * component comparison, so a year-less format is shifted by years without complaint.
 *
 * **Silent on an operand this reader cannot reduce to one declared field** — a date constant, a nested
 * operation, an extract op — and on a declaration carrying no temporal format, which is "nothing to read"
 * rather than "not a date": a `DateTimeType` may declare none and take its default.
 *
 * **The amount is judged only where it is a bare field reference**, because A12 types it as a whole OPERATION:
 * an arithmetic expression's result type is computed from the expression, and typing it from its most visible
 * operand would be a claim with nothing behind it. A numeric literal needs no judgement and a computed amount
 * is left alone.
 */
internal fun TrackedFactBase.dateArithmeticOperandFindings(): List<Finding> = declarations().flatMap { element ->
    dateArithmeticContributions(element).map { it.finding }
}

internal fun TrackedFactBase.dateArithmeticContributions(
    element: TrackedDeclaration,
    execution: ConditionExecution? = conditionExecution(element),
): List<ConditionFinding> {
    val group = element.path.substringBeforeLast('/', missingDelimiterValue = "")
    return callContributions(element, execution) { member, call ->
        val unit = addedComponentOf(call.operator) ?: return@callContributions null
        shiftDefect(call, unit, group, element)?.let { finding(it, call, element, member) }
    }
}

/** The component a shift operator spelled [operator] in this model's language adds, or null for any other. */
private fun TrackedFactBase.addedComponentOf(operator: String): Component? {
    val keywords = CONDITION_OPERATOR_KEYWORDS[conditionLanguage] ?: return null
    return ADDED_COMPONENT_OF.entries.firstOrNull { (id, _) -> keywords[id] == operator }?.value
}

/**
 * The declared date a shift [call] moves, where A12 admits the shift and this reader judges every gate it
 * asks, or null.
 *
 * A caller reading a nested shift's result needs both halves: A12 types the result as its date operand's
 * format (`CheckDatumAddOpImpl` hands the operand's type on), and a shift A12 refuses answers before any
 * operation around it is asked. So the date must be a bare declared field and the amount a numeric literal
 * or a bare number field, the two shapes [shiftDefect] decides completely.
 */
internal fun TrackedFactBase.admittedShiftField(
    call: ConditionCall,
    declaringGroup: String,
    element: TrackedDeclaration,
): TrackedDeclaration? {
    val unit = addedComponentOf(call.operator) ?: return null
    if (shiftDefect(call, unit, declaringGroup, element) != null) return null
    val date = call.operands.firstOrNull() ?: return null
    val field = bareReferenceField(date.text, declaringGroup, element, call.inAFilterBody) ?: return null
    if (temporalFormatOf(field) == null) return null
    val amount = call.operands.getOrNull(1) ?: return null
    val judged = amount.constant && amount.literal == null ||
        bareReferenceField(amount.text, declaringGroup, element, call.inAFilterBody)?.let(::effectiveFieldType) == FieldType.NUMBER
    return field.takeIf { judged }
}

/** Which datetime component each carrier adds — A12's `DateUtils.typeToComponent`, for these six tokens. */
private val ADDED_COMPONENT_OF = mapOf(
    "AddDays" to Component.DAY,
    "AddMonths" to Component.MONTH,
    "AddYears" to Component.YEAR,
    "AddHours" to Component.HOUR,
    "AddMinutes" to Component.MINUTE,
    "AddSeconds" to Component.SECOND,
)

/** The two carriers A12 refuses over a stamp outright, whatever their component question says. */
private val REFUSE_A_STAMP = setOf(Component.MONTH, Component.YEAR)

private class ShiftDefect(val code: String, val at: String, val because: String)

/** The first gate this call fails, in A12's own order, or null where none fails and where it may not judge. */
private fun TrackedFactBase.shiftDefect(
    call: ConditionCall,
    unit: Component,
    declaringGroup: String,
    element: TrackedDeclaration,
): ShiftDefect? {
    val date = call.operands.firstOrNull() ?: return null
    val field = bareReferenceField(date.text, declaringGroup, element, call.inAFilterBody) ?: return null
    if (hasAPartialPrecision(field)) {
        return ShiftDefect(
            "MVK_INVALID_DATE_TYPE", field.path,
            "declares a PARTIAL precision, and shifting a date needs one that is fully known",
        )
    }
    val format = temporalFormatOf(field) ?: return null
    val components = componentsOf(format)
    if (unit !in components && !(unit == Component.YEAR && hasBaseYear)) {
        return ShiftDefect(
            "MVK_WRONG_DATE_FORMAT_FOR_OP", field.path,
            "is declared `$format`, which does not spell the component this operator adds",
        )
    }
    if (!namesADate(components)) {
        return ShiftDefect(
            "MVK_INVALID_TIME", field.path,
            "is declared `$format`, a clock with no calendar component for the shift to land on",
        )
    }
    if (unit in REFUSE_A_STAMP && namesATime(components)) {
        return ShiftDefect(
            "MVK_WRONG_DATE_FORMAT_FOR_OP", field.path,
            "is declared `$format`, and A12 refuses a stamp under this operator although the format " +
                "spells what it adds (`A12K-620`) — shift a date-only declaration instead",
        )
    }
    val amount = call.operands.getOrNull(1) ?: return null
    val amountField = bareReferenceField(amount.text, declaringGroup, element, call.inAFilterBody) ?: return null
    val amountType = effectiveFieldType(amountField)
    return if (amountType in PLAINLY_NON_NUMBER_TYPES) {
        ShiftDefect(
            "MVK_NON_NUMERIC", amountField.path,
            "is declared `$amountType`, and the amount to shift by must be a number",
        )
    } else {
        null
    }
}

private fun finding(
    defect: ShiftDefect,
    call: ConditionCall,
    element: TrackedDeclaration,
    member: String,
): Finding = Finding(
    FindingCategory.A12_CONSTRAINT,
    FindingSeverity.ERROR,
    defect.code,
    "in `$member`: `${call.operator}` shifts `${defect.at}`, which ${defect.because}",
    element.path,
    call.spanIn(member),
)
