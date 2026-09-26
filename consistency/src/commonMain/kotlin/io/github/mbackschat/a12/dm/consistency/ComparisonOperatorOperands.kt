package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.consistency.syntax.CONDITION_OPERATOR_KEYWORDS
import io.github.mbackschat.a12.dm.consistency.syntax.ConditionComparison

/**
 * What each COMPARISON OPERATOR demands of the two sides it joins.
 *
 * `CheckVergleichsBedingungImpl.check` opens with three questions asked in a fixed order and answered before
 * any type-agreement gate runs ([KERNEL-FINDINGS KF382],
 * `rulekit.validate.laws.ComparisonOperatorOperandLawsTest`).
 *
 * | the question | class |
 * |---|---|
 * | are BOTH sides constants? | `MVK_INVALID_COMPARE_TO_CONST` |
 * | does a TOLERANCE operator have a number on both sides? | `MVK_INVALID_TYPE_FOR_TOLERANCE_OP` |
 * | does an ORDERING operator have a number or a date on both sides? | `MVK_INVALID_TYPE_FOR_COMPARISON` |
 *
 * **The constant question is asked first, which is what the grid moves the operator to show**: `1 > 2` is
 * refused for being two constants, not for being an ordering comparison, so a family that asked about the
 * operator first would name the wrong class on it.
 *
 * **The tolerance and ordering arms RETURN once satisfied**, so a comparison that clears them is never asked
 * the agreement questions at all — measured as admitted rows rather than assumed.
 *
 * **Only a bare reference or a literal is judged.** A side that is arithmetic, a nested call or an aggregate
 * carries a result type A12 computes and this reader does not, so such a comparison is left alone entirely
 * rather than typed by its most visible operand. That is the family's largest under-report and a deliberate
 * one: the alternative is a type claim with nothing behind it.
 *
 * **`True` and `False` are written-out values here like any literal**, measured on all three gates: A12
 * spells them as bare NAMES rather than as literals, so a reader that recognised only literals left the one
 * constant family a model author actually writes beside a boolean field outside every gate.
 *
 * **A constant is typed as A12 types it** ([writtenConstantOf]), so a date-shaped literal, `Today` and `Now`
 * are DATES and clear the ordering gate: `[Shipped] > "05.03.2024"` is admitted, and reading the literal as
 * text refused it here. The constant gate's one exemption is [constantGateAdmits]', and a pair it lets
 * through is asked the other two questions like any other.
 */
internal fun TrackedFactBase.comparisonOperatorOperandFindings(): List<Finding> {
    val keywords = CONDITION_OPERATOR_KEYWORDS[conditionLanguage] ?: return emptyList()
    val tolerance = TOLERANCE_OPERATORS.mapNotNull { keywords[it] }.toSet()
    return declarations().mapNotNull { element ->
        val body = element.body ?: return@mapNotNull null
        val group = element.path.substringBeforeLast('/', missingDelimiterValue = "")
        conditionTexts(element.kind, body).firstNotNullOfOrNull { (member, text) ->
            comparisonsOf(element, body, member, text)
                .firstNotNullOfOrNull { comparison -> operandFinding(comparison, tolerance, group, element, member) }
        }
    }
}

/** The four tolerance comparators, by catalog id — the arm that takes numbers and nothing else. */
private val TOLERANCE_OPERATORS = listOf(
    "DiffersWithToleranceRange1",
    "DiffersWithToleranceRange2",
    "DiffersWithToleranceRange5",
    "DiffersWithToleranceRange10",
)

/** The four ordering comparators; they are symbols, so no per-language lookup reaches them. */
private val ORDERING_OPERATORS = setOf("<", ">", "<=", ">=")

/** What one side of a comparison is, as far as the tolerance and ordering gates need to know. */
private enum class SideKind {
    NUMBER,
    DATE,
    OTHER,
}

private val ORDERABLE = setOf(SideKind.NUMBER, SideKind.DATE)

private fun TrackedFactBase.operandFinding(
    comparison: ConditionComparison,
    tolerance: Set<String>,
    declaringGroup: String,
    element: TrackedDeclaration,
    member: String,
): Finding? {
    val leftConstant = writtenConstantOf(comparison.left)
    val rightConstant = writtenConstantOf(comparison.right)
    val left = sideKindOf(comparison.left, leftConstant, declaringGroup, element, comparison.inAFilterBody)
        ?: return null
    val right = sideKindOf(comparison.right, rightConstant, declaringGroup, element, comparison.inAFilterBody)
        ?: return null
    val code = when {
        leftConstant != null && rightConstant != null && !constantGateAdmits(leftConstant, rightConstant) ->
            TWO_CONSTANTS
        comparison.operator in tolerance && !(left == SideKind.NUMBER && right == SideKind.NUMBER) -> TOLERANCE_KIND
        comparison.operator in ORDERING_OPERATORS && !(left in ORDERABLE && right in ORDERABLE) -> ORDERING_KIND
        else -> return null
    }
    return Finding(
        FindingCategory.A12_CONSTRAINT,
        FindingSeverity.ERROR,
        code,
        when (code) {
            TWO_CONSTANTS ->
                "in `$member`: `${comparison.left}` and `${comparison.right}` are both written-out values, " +
                    "so the comparison decides nothing about the document — put a field on one side"
            TOLERANCE_KIND ->
                "in `$member`: `${comparison.operator}` compares two numbers within a tolerance, and " +
                    "`${comparison.left}` / `${comparison.right}` are not both numbers — compare them with " +
                    "`==` or `!=` instead"
            else ->
                "in `$member`: `${comparison.operator}` orders its two sides, which only numbers and dates " +
                    "can be — `${comparison.left}` / `${comparison.right}` cannot; compare them with `==` " +
                    "or `!=` instead"
        },
        element.path,
        comparison.spanIn(member),
    )
}

private const val TWO_CONSTANTS = "MVK_INVALID_COMPARE_TO_CONST"

private const val TOLERANCE_KIND = "MVK_INVALID_TYPE_FOR_TOLERANCE_OP"

private const val ORDERING_KIND = "MVK_INVALID_TYPE_FOR_COMPARISON"

/**
 * What this side of the comparison is, or null when this reader will not say.
 *
 * Null is the common answer and the safe one — [bareReferenceField] carries the reasons.
 */
private fun TrackedFactBase.sideKindOf(
    side: String,
    constant: WrittenConstant?,
    declaringGroup: String,
    element: TrackedDeclaration,
    inAFilterBody: Boolean,
): SideKind? {
    when (constant) {
        is WrittenConstant.Number -> return SideKind.NUMBER
        is WrittenConstant.Temporal -> return SideKind.DATE
        is WrittenConstant.BooleanConstant, is WrittenConstant.Text -> return SideKind.OTHER
        null -> {}
    }
    val declared = bareReferenceField(side, declaringGroup, element, inAFilterBody) ?: return null
    return when (declared.baseTypeClass()) {
        BaseTypeClass.NUMBER -> SideKind.NUMBER
        BaseTypeClass.DATE -> SideKind.DATE
        null -> null
        else -> SideKind.OTHER
    }
}
