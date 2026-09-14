package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.consistency.syntax.ConditionComparison
import io.github.mbackschat.a12.dm.consistency.syntax.comparisonsIn

/**
 * What a DATE or DATE-RANGE side of a comparison demands of its partner.
 *
 * `CheckVergleichsBedingungImpl.checkDateType` and `checkDateRangeType` run back to back, ahead of the text,
 * enumeration and number arms, and each answers with a class of its own
 * ([KERNEL-FINDINGS KF386], `rulekit.validate.laws.TemporalComparisonAgreementLawsTest`).
 *
 * | the question | class |
 * |---|---|
 * | is a date's partner a date, agreeing about year, date-ness and — under equality — time? | `MVK_INVALID_COMPARE_TO_DATE` |
 * | is a date range's partner a date range of the same components? | `MVK_INVALID_COMPARE_TO_DATE_RANGE` |
 *
 * **The date arm compares COMPONENTS, never the format strings**, and only three of them: whether each
 * carries a year, whether each carries any calendar component, and — for `==` / `!=` alone — whether each
 * carries any clock component. So `dd.MM.yyyy` against `yyyy-MM` is ADMITTED though the precisions differ,
 * which A12's own comment calls the weaker condition; and a date against a date-and-time is refused under
 * equality and admitted under ordering, measured in both.
 *
 * **A date's partner of another kind lands on the DATE arm**, whichever arm would otherwise own it: a
 * number, a text and a date range each draw `MVK_INVALID_COMPARE_TO_DATE` rather than that partner's own
 * class, because this walk runs first. Measured on all three, in both operand orders.
 *
 * **A DATE FRAGMENT is a date carrier here**, measured rather than inherited from the aggregate family's
 * treatment of it: matching components are admitted and differing ones draw the date class.
 *
 * **The date-range arm demands EXACT component agreement and ignores both the format string and the
 * separator** — two ranges spelled `dd.MM.yyyy`/`-` and `yyyy-MM-dd`/`/` are admitted. A12 passes `false`
 * for the base year in this arm alone, and that literal is transcribed rather than shared with the date
 * arm's supplemented test.
 *
 * **The range arm's own ORDERING refusal is unreachable and is not reported.** A date range is not
 * orderable, so [comparisonOperatorOperandFindings]' gate answers `MVK_INVALID_TYPE_FOR_COMPARISON` first —
 * measured, because naming the range class there would be the wrong class on a correct rejection.
 *
 * **Only the six comparison operators that REACH this walk are judged.** The tolerance and pattern arms
 * return before it, so a tolerance comparison draws its own class and nothing else — measured, because the
 * date arm otherwise co-fires on the very pair the tolerance arm has already refused. And under ORDERING the
 * walk is reached only when both sides clear the orderability gate, so a date ordered against a number is
 * this class and a date ordered against a text is `MVK_INVALID_TYPE_FOR_COMPARISON`, measured as the pair
 * that separates them.
 *
 * **Both sides must be DECLARATIONS.** A written-out value is deliberately left unjudged: A12 types a
 * date-shaped string CONSTANT as a date, so treating `"01.01.2020"` as text would refuse a comparison A12
 * admits — the one direction this project forbids. `Today`, `Now` and `BaseYear` are left alone for the
 * neighbouring reason, that their format is not one this reader can name.
 */
fun PreparedModel.temporalComparisonAgreementFindings(): List<Finding> = elements.mapNotNull { element ->
    val body = element.body ?: return@mapNotNull null
    val group = element.path.substringBeforeLast('/', missingDelimiterValue = "")
    conditionTexts(element.kind, body).firstNotNullOfOrNull { (member, text) ->
        comparisonsIn(text, conditionLanguage, syntaxEntryOf(member))
            .firstNotNullOfOrNull { comparison -> temporalFinding(comparison, group, element, member) }
    }
}

private const val EQUAL = "=="

private const val NOT_EQUAL = "!="

/** The four ordering comparators; they are symbols, so no per-language lookup reaches them. */
private val ORDERING_OPERATORS = setOf("<", ">", "<=", ">=")

private const val DATE = "MVK_INVALID_COMPARE_TO_DATE"

private const val RANGE = "MVK_INVALID_COMPARE_TO_DATE_RANGE"

/** What one side is, as far as the two arms need to know. */
private sealed interface TemporalSide {

    /** A declaration A12 types as a date, at the components its format carries. */
    data class Date(val path: String, val components: Set<Component>) : TemporalSide

    /** A `DateRangeType` declaration, at the components its format carries. */
    data class Range(val path: String, val components: Set<Component>) : TemporalSide

    /** A number declaration — not a date, but ORDERABLE, which decides whether this walk is reached. */
    data class Number(val path: String) : TemporalSide

    /** A declaration of any other kind — a partner neither arm admits, and not orderable. */
    data class Other(val path: String) : TemporalSide
}

private val TemporalSide.isOrderable: Boolean
    get() = this is TemporalSide.Date || this is TemporalSide.Number

/** The class this comparison fails on, and the side that fails it as the author wrote it. */
private data class TemporalDefect(val code: String, val wrote: String)

private fun PreparedModel.temporalFinding(
    comparison: ConditionComparison,
    declaringGroup: String,
    element: DeclaredElement,
    member: String,
): Finding? {
    val left = sideOf(comparison.left, declaringGroup, element) ?: return null
    val right = sideOf(comparison.right, declaringGroup, element) ?: return null
    val equality = comparison.operator == EQUAL || comparison.operator == NOT_EQUAL
    if (!equality) {
        // The tolerance and pattern arms RETURN before this walk, and an ordering comparison reaches it only
        // once both sides have cleared the orderability gate ahead of it.
        if (comparison.operator !in ORDERING_OPERATORS || !left.isOrderable || !right.isOrderable) {
            return null
        }
    }
    // The range arm's third branch refuses a non-equality operator and cannot be reached: a date range is
    // not orderable, so the operator gate answers first. Judging one here would name this class where A12
    // names that one — which is why only the DATE arm is asked of a non-equality comparison.
    val defect = dateDefect(comparison, left, right, equality)
        ?: (if (equality) rangeDefect(comparison, left, right) else null)
        ?: return null
    return Finding(
        FindingCategory.A12_CONSTRAINT,
        FindingSeverity.ERROR,
        defect.code,
        if (defect.code == DATE) {
            "in `$member`: `${defect.wrote}` cannot stand against a date — a date compares only to a date " +
                "whose format carries a year if its own does, a calendar component if its own does, and, " +
                "under `$EQUAL` / `$NOT_EQUAL`, a clock component if its own does"
        } else {
            "in `$member`: `${defect.wrote}` cannot stand against a date range — a date range compares " +
                "only to a date range declaring the SAME components, whatever format string or separator " +
                "either spells"
        },
        element.path,
    )
}

/** Why this pair fails the date arm, or null. */
private fun PreparedModel.dateDefect(
    comparison: ConditionComparison,
    left: TemporalSide,
    right: TemporalSide,
    equality: Boolean,
): TemporalDefect? {
    val date = (left as? TemporalSide.Date) ?: (right as? TemporalSide.Date) ?: return null
    val partner = if (left === date) right else left
    val wrote = if (left === date) comparison.right else comparison.left
    if (partner !is TemporalSide.Date) {
        return TemporalDefect(DATE, wrote)
    }
    val yearDisagrees = !hasBaseYear &&
        (Component.YEAR in date.components) != (Component.YEAR in partner.components)
    val calendarDisagrees = namesADate(date.components) != namesADate(partner.components)
    val clockDisagrees = equality && namesATime(date.components) != namesATime(partner.components)
    return if (yearDisagrees || calendarDisagrees || clockDisagrees) TemporalDefect(DATE, wrote) else null
}

/**
 * Why this pair fails the date-range arm, or null.
 *
 * A12 passes `false` for the base year in this arm alone, where the date arm supplements — transcribed
 * rather than shared, because the two arms disagree about it on purpose.
 */
private fun rangeDefect(
    comparison: ConditionComparison,
    left: TemporalSide,
    right: TemporalSide,
): TemporalDefect? {
    val range = (left as? TemporalSide.Range) ?: (right as? TemporalSide.Range) ?: return null
    val partner = if (left === range) right else left
    val wrote = if (left === range) comparison.right else comparison.left
    if (partner !is TemporalSide.Range) {
        return TemporalDefect(RANGE, wrote)
    }
    return if (range.components != partner.components) TemporalDefect(RANGE, wrote) else null
}

/** What this side is, or null wherever this reader will not say — see [bareReferenceField]. */
private fun PreparedModel.sideOf(
    side: String,
    declaringGroup: String,
    element: DeclaredElement,
): TemporalSide? {
    val declared = bareReferenceField(side, declaringGroup, element) ?: return null
    declaredTemporalFormatOf(declared)?.let {
        return TemporalSide.Date(declared.path, componentsOf(it))
    }
    declaredDateRangeFormatOf(declared)?.let {
        return TemporalSide.Range(declared.path, componentsOf(it))
    }
    // A declaration whose kind this reader knows and that carries no temporal format is an ordinary
    // partner; one whose temporal body it cannot read is not, and leaves the comparison unjudged.
    if (declared.declaredFieldType() == "NumberType") {
        return TemporalSide.Number(declared.path)
    }
    return if (declared.declaredFieldType() in DATED_TYPES) null else TemporalSide.Other(declared.path)
}

/** The kinds whose format member decides the arm; an unreadable body among them means "cannot say". */
private val DATED_TYPES =
    setOf("DateType", "TimeType", "DateTimeType", "DateFragmentType", "DateRangeType")
