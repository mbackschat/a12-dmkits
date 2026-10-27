package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.consistency.syntax.CONDITION_OPERATOR_IDENTITIES
import io.github.mbackschat.a12.dm.consistency.syntax.ConditionCall

/**
 * What a temporal EXTRACTOR demands of the date it reads, asked once its operand has passed the date slot.
 *
 * `CheckDatumExtractOpImpl.check` splits on the operator. The seven NUMBER extractors ask three questions in a
 * fixed order; the two that yield a date or a time ask one ([KERNEL-FINDINGS KF529],
 * `adapter.laws.CheckerTemporalExtractorAgreementTest`, mirrored by `TemporalExtractorOperandsTest`):
 *
 * | # | the question | class |
 * |---|---|---|
 * | 1 | a partially known date field: is the extracted component one its precision leaves known? | `MVK_INVALID_DATE_TYPE` |
 * | 2 | is the operand a quoted date literal? | `MVK_NO_CONST` |
 * | 3 | does the operand's format carry the extracted component? A Base Year answers it for the year | `MVK_WRONG_DATE_FORMAT_FOR_OP` |
 * | — | `DateFromDateTime`, `TimeFromDateTime`: does the format carry all six components? | `MVK_WRONG_DATE_FORMAT_FOR_OP` |
 *
 * **The partial-precision gate is not the format gate.** Every clock component is refused, and so is the
 * calendar component the precision leaves open: the day under `DAY_OPTIONAL`, the month and the day under
 * `MONTH_OPTIONAL`, everything under `YEAR_OPTIONAL`. `QuarterFromDate` extracts the month.
 *
 * **The operand is typed by [TemporalOperandReader]**, as the format A12 registers for it.
 *
 * **Silent where another family owns the answer** — a slot defect ([CheckFamily.DATE_ARGUMENT_SLOT]), a refused
 * shift or `DateTime(…)` ([CheckFamily.DATE_TIME_CONSTRUCTION_OPERANDS]), a missing Base Year, a starred operand
 * A12 refuses as a wildcard first — and on what the reader cannot type.
 */
internal fun TrackedFactBase.temporalExtractorContributions(
    element: TrackedDeclaration,
    execution: ConditionExecution? = conditionExecution(element),
): List<ConditionFinding> {
    if (execution == null) return emptyList()
    val identities = CONDITION_OPERATOR_IDENTITIES[conditionLanguage] ?: return emptyList()
    val reader = TemporalOperandReader(this, identities, execution, element)
    return callContributions(element, execution) { member, call ->
        val extractor = identities[call.operator]?.let(Extractor::of) ?: return@callContributions null
        reader.extractorDefect(extractor, call)?.let { finding(it, call, element, member) }
    }
}

/** The nine extractors, with the component each number extractor reads — A12's `DateUtils.typeToComponent`. */
internal enum class Extractor(val id: String, val component: Component?) {
    YEAR_FROM_DATE("YearFromDate", Component.YEAR),
    QUARTER_FROM_DATE("QuarterFromDate", Component.MONTH),
    MONTH_FROM_DATE("MonthFromDate", Component.MONTH),
    DAY_FROM_DATE("DayFromDate", Component.DAY),
    HOURS_FROM_TIME("HoursFromTime", Component.HOUR),
    MINUTES_FROM_TIME("MinutesFromTime", Component.MINUTE),
    SECONDS_FROM_TIME("SecondsFromTime", Component.SECOND),
    DATE_FROM_DATE_TIME("DateFromDateTime", null),
    TIME_FROM_DATE_TIME("TimeFromDateTime", null);

    companion object {
        fun of(id: String): Extractor? = entries.firstOrNull { it.id == id }
    }
}

private val CLOCK = setOf(Component.HOUR, Component.MINUTE, Component.SECOND)

private class ExtractorDefect(val code: String, val because: String)

/** The first gate [call] fails, in A12's own order, or null where none fails and where it may not judge. */
private fun TemporalOperandReader.extractorDefect(extractor: Extractor, call: ConditionCall): ExtractorDefect? {
    val operand = call.operands.singleOrNull() ?: return null
    val from = typed(operand, call.inAFilterBody) ?: return null
    val unit = extractor.component ?: return if (from.components.containsAll(Component.entries)) null else
        ExtractorDefect("MVK_WRONG_DATE_FORMAT_FOR_OP",
            "needs a stamp carrying all six date and clock components, and `${operand.text}` does not")
    if (from is ExtractedFrom.Field && from.precision != null &&
        (unit in CLOCK || unit in OPEN_UNDER_PRECISION.getValue(from.precision))) {
        return ExtractorDefect("MVK_INVALID_DATE_TYPE",
            "reads `${from.path}`, declared `${from.precision}`, which leaves the component it extracts unknown")
    }
    if (from is ExtractedFrom.Literal) {
        return ExtractorDefect("MVK_NO_CONST",
            "is handed the constant `${operand.text}`, whose components are already known — extract from a field")
    }
    if (unit in from.components || unit == Component.YEAR && facts.hasBaseYear) return null
    return ExtractorDefect("MVK_WRONG_DATE_FORMAT_FOR_OP",
        "reads `${operand.text}`, whose format does not carry the component it extracts")
}

private fun finding(
    defect: ExtractorDefect,
    call: ConditionCall,
    element: TrackedDeclaration,
    member: String,
): Finding = Finding(
    FindingCategory.A12_CONSTRAINT,
    FindingSeverity.ERROR,
    defect.code,
    "in `$member`: `${call.operator}` ${defect.because}",
    element.path,
    call.spanIn(member),
)
