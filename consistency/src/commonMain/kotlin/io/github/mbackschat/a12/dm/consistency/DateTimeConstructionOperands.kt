package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.consistency.syntax.CONDITION_OPERATOR_IDENTITIES
import io.github.mbackschat.a12.dm.consistency.syntax.ConditionCall

/**
 * What `DateTime(date, time)` demands of its two operands, asked once each has passed its own slot.
 *
 * `CheckDateTimeKonstruktImpl.check` asks the Date operand three questions and then the Time operand one, and
 * answers at the first that fails ([KERNEL-FINDINGS KF627], `adapter.laws.CheckerDateTimeConstructionAgreementTest`):
 *
 * | # | the operand | the question | class |
 * |---|---|---|---|
 * | 1 | Date | does its format carry a calendar component and a year, or does a Base Year supply the year? | `MVK_WRONG_DATE_FORMAT_FOR_OP` |
 * | 2 | Date | does its format carry no clock component? | `MVK_WRONG_DATE_FORMAT_FOR_OP` |
 * | 3 | Date | is a field operand fully known? | `MVK_INVALID_DATE_TYPE` |
 * | 4 | Time | does its format carry a clock component and no calendar component? | `MVK_WRONG_DATE_FORMAT_FOR_OP` |
 *
 * **The precision gate comes after the format gates and is a field's alone**, so a partially known date of a
 * full format is refused whatever stands in the Time slot, while a constant or a nested operation never draws
 * it. **No gate asks for a COMPLETE date**: a `yyyy-MM` or a `yyyy` field is a Date operand, and a Base Year
 * admits a year-less one, where it admits nothing in the Time slot.
 *
 * **Silent on the Time operand while the Date operand cannot be typed**, because A12 asks the Date first and a
 * defect this reader cannot see there would answer in its place — and silent on what [TemporalOperandReader]
 * cannot type, which includes the `ValueAsDate` placement A12 exempts from the Date gates.
 */
internal fun TrackedFactBase.dateTimeConstructionContributions(
    element: TrackedDeclaration,
    execution: ConditionExecution? = conditionExecution(element),
): List<ConditionFinding> {
    if (execution == null) return emptyList()
    val identities = CONDITION_OPERATOR_IDENTITIES[conditionLanguage] ?: return emptyList()
    val reader = TemporalOperandReader(this, identities, execution, element)
    return callContributions(element, execution) { member, call ->
        if (identities[call.operator] != "DateTime") return@callContributions null
        reader.dateTimeDefect(call)?.let { finding(it, call, element, member) }
    }
}

internal class DateTimeDefect(val code: String, val because: String)

/** The first gate a `DateTime` [call] fails, in A12's own order, or null where none fails and where it may not judge. */
internal fun TemporalOperandReader.dateTimeDefect(call: ConditionCall): DateTimeDefect? {
    val (dateOperand, timeOperand) = call.operands.takeIf { it.size == 2 } ?: return null
    val date = typed(dateOperand, call.inAFilterBody) ?: return null
    val dated = namesADate(date.components) && (Component.YEAR in date.components || facts.hasBaseYear)
    if (!dated || namesATime(date.components)) {
        return DateTimeDefect("MVK_WRONG_DATE_FORMAT_FOR_OP",
            "is handed `${dateOperand.text}` as its date, whose format is not a date with a year and no clock")
    }
    if (date is ExtractedFrom.Field && date.precision != null) {
        return DateTimeDefect("MVK_INVALID_DATE_TYPE",
            "is handed `${date.path}` as its date, declared `${date.precision}`, which leaves part of it unknown")
    }
    val time = typed(timeOperand, call.inAFilterBody) ?: return null
    if (namesADate(time.components) || !namesATime(time.components)) {
        return DateTimeDefect("MVK_WRONG_DATE_FORMAT_FOR_OP",
            "is handed `${timeOperand.text}` as its time, whose format is not a clock with no date")
    }
    return null
}

private fun finding(defect: DateTimeDefect, call: ConditionCall, element: TrackedDeclaration, member: String) =
    Finding(
        FindingCategory.A12_CONSTRAINT,
        FindingSeverity.ERROR,
        defect.code,
        "in `$member`: `${call.operator}` ${defect.because}",
        element.path,
        call.spanIn(member),
    )
