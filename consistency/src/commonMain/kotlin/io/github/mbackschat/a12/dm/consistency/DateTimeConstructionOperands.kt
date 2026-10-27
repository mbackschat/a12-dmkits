package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.consistency.syntax.CONDITION_OPERATOR_IDENTITIES

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
 * **A `ValueAsDate` Date operand skips rows 1–3**, as `CheckDateTimeKonstruktImpl` exempts that form, and the
 * Time operand is still asked ([KERNEL-FINDINGS KF643]). **Silent on the Time operand while any other Date
 * operand cannot be typed**, because A12 asks the Date first and a defect this reader cannot see there would
 * answer in its place — and silent on what [TemporalOperandReader] cannot type.
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
        reader.dateTimeDefect(call)?.findingAt(call, element, member)
    }
}
