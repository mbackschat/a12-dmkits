package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.consistency.syntax.CONDITION_OPERATOR_KEYWORDS
import io.github.mbackschat.a12.dm.consistency.syntax.CallOperand
import io.github.mbackschat.a12.dm.consistency.syntax.CallShape
import io.github.mbackschat.a12.dm.consistency.syntax.ConditionCall
import io.github.mbackschat.a12.dm.consistency.syntax.inAFilterBody
import io.github.mbackschat.a12.dm.consistency.syntax.operandOf

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
 * **The operand's format is whatever A12 registered for it**, and that is how a nested operation is read:
 * `Time(…)` and `TimeFromDateTime(…)` are a clock, `DateFromDateTime(…)`, `Today` and a declared Base Year are
 * `dd.MM.yyyy`, `Now` and `DateTime(…)` are a stamp, a two-component `Date(…)` is year-less, a shift carries
 * its date's format, and a range endpoint carries its range's format unless the range reads its year
 * `FROM` or `TO`, which makes it a full date. A nested operation is typed only where this reader can see that
 * A12 admits it, because a refused operation answers before the extractor is asked.
 *
 * **Silent where another family owns the answer** — a slot defect ([CheckFamily.DATE_ARGUMENT_SLOT]), a refused
 * shift, a missing Base Year — and on what it cannot type: a starred, keyed or unresolved operand, a
 * declaration with no temporal format, a four-component `Date(…)` or a construction over a `TypeDefType`
 * component, whose slot gates [CheckFamily.DATE_CONSTRUCTION_SLOT] delegates, and a `DateTime(…)` over a
 * partially known date, whose own refusal is `MVK_INVALID_DATE_TYPE` rather than a format class.
 */
internal fun TrackedFactBase.temporalExtractorContributions(
    element: TrackedDeclaration,
    execution: ConditionExecution? = conditionExecution(element),
): List<ConditionFinding> {
    if (execution == null) return emptyList()
    val identities = CONDITION_OPERATOR_KEYWORDS[conditionLanguage]?.entries
        ?.associate { (id, spelling) -> spelling to id } ?: return emptyList()
    val reader = ExtractorOperandReader(this, identities, execution, element)
    return callContributions(element, execution) { member, call ->
        val extractor = identities[call.operator]?.let(Extractor::of) ?: return@callContributions null
        reader.defect(extractor, call)?.let { finding(it, call, element, member) }
    }
}

/** The nine extractors, with the component each number extractor reads — A12's `DateUtils.typeToComponent`. */
private enum class Extractor(val id: String, val component: Component?) {
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

/** The calendar components each partial precision leaves open, beside every clock component. */
private val OPEN_UNDER_PRECISION = mapOf(
    "DAY_OPTIONAL" to setOf(Component.DAY),
    "MONTH_OPTIONAL" to setOf(Component.MONTH, Component.DAY),
    "YEAR_OPTIONAL" to setOf(Component.YEAR, Component.MONTH, Component.DAY),
)

private val CLOCK = setOf(Component.HOUR, Component.MINUTE, Component.SECOND)

/** What A12 typed an extractor's operand as, with the provenance the first two gates ask about. */
private sealed interface ExtractedFrom {
    val components: Set<Component>

    /** A declared field, whose partial [precision] is the first gate's question. */
    data class Field(override val components: Set<Component>, val path: String, val precision: String?) : ExtractedFrom

    /** A quoted date literal, which a number extractor refuses outright. */
    data class Literal(override val components: Set<Component>) : ExtractedFrom

    /** A keyword or a nested operation, typed by the format A12 registers for it. */
    data class Computed(override val components: Set<Component>) : ExtractedFrom
}

private class ExtractorDefect(val code: String, val because: String)

private class ExtractorOperandReader(
    val facts: TrackedFactBase,
    val identities: Map<String, String>,
    val execution: ConditionExecution,
    val element: TrackedDeclaration,
) {
    private val group = element.path.substringBeforeLast('/', missingDelimiterValue = "")

    /** The first gate [call] fails, in A12's own order, or null where none fails and where it may not judge. */
    fun defect(extractor: Extractor, call: ConditionCall): ExtractorDefect? {
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

    private fun typed(operand: CallOperand, inAFilterBody: Boolean): ExtractedFrom? = with(facts) {
        execution.callsByStart[operand.at]?.takeIf { it.end == operand.end }?.let { return operation(it) }
        if (isBaseYearConstant(operand.text)) return if (hasBaseYear) ExtractedFrom.Computed(componentsOf(DATE_FORMAT)) else null
        when (val constant = writtenConstantOf(operand.text)) {
            is WrittenConstant.Temporal -> return if (constant.keyword) ExtractedFrom.Computed(constant.components)
                else ExtractedFrom.Literal(constant.components)
            null -> Unit
            else -> return null
        }
        val field = bareReferenceField(operand.text, group, element, inAFilterBody) ?: return null
        val format = declaredTemporalFormatOf(field) ?: return null
        ExtractedFrom.Field(componentsOf(format), field.path, declaredPartialPrecisionOf(field))
    }

    /** The format A12 registers for a nested operation it admits, or null where this reader cannot say. */
    private fun operation(shape: CallShape): ExtractedFrom? = with(facts) {
        val operands = shape.operands.map { operandOf(it, conditionLanguage, shape.source) }
        val inBody = shape.inAFilterBody(conditionLanguage)
        val format = when (identities[shape.operator]) {
            "DateFromDateTime", "TimeFromDateTime" -> {
                // Where this family judges the inner stamp, a refusal is already reported at the inner call. The
                // check is for an operand it cannot type, where another class may answer first: silence, not a
                // guess.
                val from = operands.singleOrNull()?.let { typed(it, inBody) } ?: return null
                if (!from.components.containsAll(Component.entries)) return null
                if (identities[shape.operator] == "DateFromDateTime") DATE_FORMAT else TIME_FORMAT
            }
            "Time" -> TIME_FORMAT.takeIf { operands.isNotEmpty() && operands.all { judgedComponent(it, inBody) } }
            "Date" -> when (operands.size) {
                2 -> YEARLESS_DATE_FORMAT
                3 -> DATE_FORMAT
                else -> null
            }?.takeIf { operands.all { judgedComponent(it, inBody) } }
            "DateTime" -> {
                val date = operands.firstOrNull()?.let { typed(it, inBody) }
                STAMP_FORMAT.takeIf { operands.size == 2 && !(date is ExtractedFrom.Field && date.precision != null) }
            }
            "StartOfDateRange", "EndOfDateRange" -> endpointFormat(operands.singleOrNull() ?: return null, inBody)
            else -> admittedShiftField(ConditionCall(shape.operator, shape.at, operands, shape.end, inBody), group, element)
                ?.let(::declaredTemporalFormatOf)
        } ?: return null
        ExtractedFrom.Computed(componentsOf(format))
    }

    /**
     * Whether a construction's component is one whose slot gates [CheckFamily.DATE_CONSTRUCTION_SLOT] answers
     * completely: a quoted constant, a nested operation, or a bare number or text field. A `TypeDefType`
     * component is the one it delegates.
     */
    private fun judgedComponent(operand: CallOperand, inAFilterBody: Boolean): Boolean = with(facts) {
        operand.literal != null || execution.callsByStart[operand.at]?.end == operand.end ||
            bareReferenceField(operand.text, group, element, inAFilterBody)?.declaredFieldType()
                .let { it == FieldType.NUMBER || it == FieldType.STRING }
    }

    /** A range endpoint's format: the range's own, or a full date where the range reads its year from one end. */
    private fun endpointFormat(operand: CallOperand, inAFilterBody: Boolean): String? = with(facts) {
        if (isBaseYearConstant(operand.text)) return if (hasBaseYear) DATE_FORMAT else null
        val range = bareReferenceField(operand.text, group, element, inAFilterBody) ?: return null
        val format = declaredDateRangeFormatOf(range) ?: return null
        val interpretation = declaredRangeYearInterpretationOf(range)
        if (interpretation == null || interpretation == "STANDARD") format else DATE_FORMAT
    }
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
