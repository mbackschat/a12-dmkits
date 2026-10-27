package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.consistency.syntax.CallOperand
import io.github.mbackschat.a12.dm.consistency.syntax.CallShape
import io.github.mbackschat.a12.dm.consistency.syntax.ConditionCall
import io.github.mbackschat.a12.dm.consistency.syntax.asCall
import io.github.mbackschat.a12.dm.consistency.syntax.inAFilterBody

/** What A12 typed a temporal operand as, with the provenance the extractor and construction gates ask about. */
internal sealed interface ExtractedFrom {
    val components: Set<Component>

    /** A declared field, whose partial [precision] is a gate of its own. */
    data class Field(override val components: Set<Component>, val path: String, val precision: String?) : ExtractedFrom

    /** A quoted date literal, which a number extractor refuses outright. */
    data class Literal(override val components: Set<Component>) : ExtractedFrom

    /** A keyword or a nested operation, typed by the format A12 registers for it. */
    data class Computed(override val components: Set<Component>) : ExtractedFrom
}

/**
 * The format A12 registers for a temporal operand, shared by every family that asks a question of it.
 *
 * **The operand's format is whatever A12 registered for it**, and that is how a nested operation is read:
 * `Time(…)` and `TimeFromDateTime(…)` are a clock, `DateFromDateTime(…)`, `Today` and a declared Base Year are
 * `dd.MM.yyyy`, `Now` and `DateTime(…)` are a stamp, a two-component `Date(…)` is year-less, a shift carries
 * its date's format, and a range endpoint carries its range's format unless the range reads its year
 * `FROM` or `TO`, which makes it a full date. A nested operation is typed only where this reader can see that
 * A12 admits it, because a refused operation answers before the operation around it is asked.
 *
 * **A key-selected operand is typed as the field it selects**, its precision and format included. Null on what
 * this reader cannot type: an unresolved operand, a declaration with no temporal format, a four-component
 * `Date(…)` or a construction over a component typed through an unresolvable type definition.
 */
internal class TemporalOperandReader(
    val facts: TrackedFactBase,
    val identities: Map<String, String>,
    val execution: ConditionExecution,
    val element: TrackedDeclaration,
) {
    private val group = element.path.substringBeforeLast('/', missingDelimiterValue = "")

    fun typed(operand: CallOperand, inAFilterBody: Boolean): ExtractedFrom? = with(facts) {
        execution.callsByStart[operand.at]?.takeIf { it.end == operand.end }?.let { return operation(it) }
        if (isBaseYearConstant(operand.text)) return if (hasBaseYear) ExtractedFrom.Computed(componentsOf(DATE_FORMAT)) else null
        when (val constant = writtenConstantOf(operand.text)) {
            is WrittenConstant.Temporal -> return if (constant.keyword) ExtractedFrom.Computed(constant.components)
                else ExtractedFrom.Literal(constant.components)
            null -> Unit
            else -> return null
        }
        val field = bareReferenceField(operand.text, group, element, inAFilterBody) ?: return null
        val format = temporalFormatOf(field) ?: return null
        ExtractedFrom.Field(componentsOf(format), field.path, partialPrecisionOf(field))
    }

    /** Whether [operand] is a whole `ValueAsDate` call, which `DateTime`'s Date gates do not ask (KF643). */
    fun isValueAsDate(operand: CallOperand): Boolean =
        execution.callsByStart[operand.at]?.takeIf { it.end == operand.end }?.let { identities[it.operator] } == "ValueAsDate"

    /** The format A12 registers for a nested operation it admits, or null where this reader cannot say. */
    private fun operation(shape: CallShape): ExtractedFrom? = with(facts) {
        val inBody = shape.inAFilterBody(conditionLanguage)
        val call = shape.asCall(conditionLanguage, inBody)
        val operands = call.operands
        val format = when (identities[shape.operator]) {
            "DateFromDateTime", "TimeFromDateTime" -> {
                // Where the extractor family judges the inner stamp, a refusal is already reported at the inner
                // call. The check is for an operand it cannot type, where another class may answer first:
                // silence, not a guess.
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
            "DateTime" -> STAMP_FORMAT.takeIf { operands.size == 2 && dateTimeDefect(call) == null }
            "StartOfDateRange", "EndOfDateRange" -> endpointFormat(operands.singleOrNull() ?: return null, inBody)
            else -> admittedShiftField(call, group, element)
                ?.let { temporalFormatOf(it) }
        } ?: return null
        ExtractedFrom.Computed(componentsOf(format))
    }

    /**
     * Whether a construction's component is one whose slot gates [CheckFamily.DATE_CONSTRUCTION_SLOT] answers
     * completely: a quoted constant, a nested extract operation, or a bare number or text field, typed through its
     * definition where it has one.
     */
    private fun judgedComponent(operand: CallOperand, inAFilterBody: Boolean): Boolean = with(facts) {
        operand.literal != null ||
            execution.callsByStart[operand.at]?.takeIf { it.end == operand.end }
                ?.let { shape -> identities[shape.operator]?.let(Extractor::of) } != null ||
            bareReferenceField(operand.text, group, element, inAFilterBody)?.let(::effectiveFieldType)
                .let { it == FieldType.NUMBER || it == FieldType.STRING }
    }

    /** A range endpoint's format: the range's own, or a full date where the range reads its year from one end. */
    private fun endpointFormat(operand: CallOperand, inAFilterBody: Boolean): String? = with(facts) {
        if (isBaseYearConstant(operand.text)) return if (hasBaseYear) DATE_FORMAT else null
        val range = bareReferenceField(operand.text, group, element, inAFilterBody) ?: return null
        val format = dateRangeFormatOf(range) ?: return null
        val interpretation = rangeYearInterpretationOf(range)
        if (interpretation == null || interpretation == "STANDARD") format else DATE_FORMAT
    }
}

/** The first gate a temporal call fails: A12's class, and what the call was handed that fails it. */
internal class TemporalDefect(val code: String, val because: String)

/** The finding a [TemporalDefect] reports at its [call], spanned in the [member] that holds it. */
internal fun TemporalDefect.findingAt(call: ConditionCall, element: TrackedDeclaration, member: String): Finding = Finding(
    FindingCategory.A12_CONSTRAINT,
    FindingSeverity.ERROR,
    code,
    "in `$member`: `${call.operator}` $because",
    element.path,
    call.spanIn(member),
)

/**
 * The first gate a `DateTime` [call] fails, in A12's own order, or null where none fails and where it may not judge.
 *
 * The reader asks this gate before it types a nested `DateTime`, so it lives with the reader rather than with
 * the family that reports it ([TrackedFactBase.dateTimeConstructionContributions]).
 */
internal fun TemporalOperandReader.dateTimeDefect(call: ConditionCall): TemporalDefect? {
    // Any other arity is a parse error the recognizer reports first, so declining here changes no verdict
    // (`adapter.laws.CheckerDateTimeConstructionAgreementTest`).
    val (dateOperand, timeOperand) = call.operands.takeIf { it.size == 2 } ?: return null
    // `ValueAsDate` is exempt from the Date gates, and the Time operand is still asked (KF643).
    if (!isValueAsDate(dateOperand)) {
        val date = typed(dateOperand, call.inAFilterBody) ?: return null
        val dated = namesADate(date.components) && (Component.YEAR in date.components || facts.hasBaseYear)
        if (!dated || namesATime(date.components)) {
            return TemporalDefect("MVK_WRONG_DATE_FORMAT_FOR_OP",
                "is handed `${dateOperand.text}` as its date, whose format is not a date with a year and no clock")
        }
        if (date is ExtractedFrom.Field && date.precision != null) {
            return TemporalDefect("MVK_INVALID_DATE_TYPE",
                "is handed `${date.path}` as its date, declared `${date.precision}`, which leaves part of it unknown")
        }
    }
    val time = typed(timeOperand, call.inAFilterBody) ?: return null
    if (namesADate(time.components) || !namesATime(time.components)) {
        return TemporalDefect("MVK_WRONG_DATE_FORMAT_FOR_OP",
            "is handed `${timeOperand.text}` as its time, whose format is not a clock with no date")
    }
    return null
}
