package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.consistency.syntax.CONDITION_OPERATOR_KEYWORDS
import io.github.mbackschat.a12.dm.consistency.syntax.ConditionComparison
import io.github.mbackschat.a12.dm.consistency.syntax.TokenKind
import io.github.mbackschat.a12.dm.consistency.syntax.comparisonsIn
import io.github.mbackschat.a12.dm.consistency.syntax.referencesIn
import io.github.mbackschat.a12.dm.consistency.syntax.tokenize

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
 * **Silent wherever either side is `Today` or `Now`.** Those carry A12's DATETIME base type, which is the
 * one exemption in the constant gate — two constants are admitted when exactly one of them is such a
 * keyword — and getting that arm wrong would refuse a legal comparison.
 */
fun PreparedModel.comparisonOperatorOperandFindings(): List<Finding> {
    val keywords = CONDITION_OPERATOR_KEYWORDS[conditionLanguage] ?: return emptyList()
    val tolerance = TOLERANCE_OPERATORS.mapNotNull { keywords[it] }.toSet()
    val temporal = TEMPORAL_CONSTANTS.mapNotNull { keywords[it] }.toSet()
    return elements.mapNotNull { element ->
        val body = element.body ?: return@mapNotNull null
        val group = element.path.substringBeforeLast('/', missingDelimiterValue = "")
        conditionTexts(element.kind, body).firstNotNullOfOrNull { (member, text) ->
            comparisonsIn(text, conditionLanguage, syntaxEntryOf(member))
                .firstNotNullOfOrNull { comparison ->
                    operandFinding(comparison, tolerance, temporal, group, element, member)
                }
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

/** The two constants carrying A12's DATETIME base type, which the constant gate exempts. */
private val TEMPORAL_CONSTANTS = listOf("Today", "Now")

/** The four ordering comparators; they are symbols, so no per-language lookup reaches them. */
private val ORDERING_OPERATORS = setOf("<", ">", "<=", ">=")

/** What one side of a comparison is, as far as these three gates need to know. */
private enum class SideKind {
    NUMBER_CONSTANT,
    OTHER_CONSTANT,
    NUMBER_FIELD,
    DATE_FIELD,
    OTHER_FIELD,
}

private val CONSTANTS = setOf(SideKind.NUMBER_CONSTANT, SideKind.OTHER_CONSTANT)

private val NUMBER_LIKE = setOf(SideKind.NUMBER_CONSTANT, SideKind.NUMBER_FIELD)

private val ORDERABLE = NUMBER_LIKE + SideKind.DATE_FIELD

private fun PreparedModel.operandFinding(
    comparison: ConditionComparison,
    tolerance: Set<String>,
    temporal: Set<String>,
    declaringGroup: String,
    element: DeclaredElement,
    member: String,
): Finding? {
    if (namesATemporalConstant(comparison.left, temporal) ||
        namesATemporalConstant(comparison.right, temporal)
    ) {
        return null
    }
    val left = sideKindOf(comparison.left, declaringGroup, element) ?: return null
    val right = sideKindOf(comparison.right, declaringGroup, element) ?: return null
    val code = when {
        left in CONSTANTS && right in CONSTANTS -> TWO_CONSTANTS
        comparison.operator in tolerance && !(left in NUMBER_LIKE && right in NUMBER_LIKE) -> TOLERANCE_KIND
        comparison.operator in ORDERING_OPERATORS && !(left in ORDERABLE && right in ORDERABLE) ->
            ORDERING_KIND
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
    )
}

/** The token kinds one entity spec may be made of; anything else makes the side an expression. */
private val REFERENCE_TOKENS = setOf(TokenKind.NAME, TokenKind.QUOTED_NAME, TokenKind.PUNCTUATION)

/** The punctuation an entity spec may carry: its brackets, its path steps, and a repetition star. */
private val SPEC_PUNCTUATION = setOf("[", "]", "/", "*", ".")

private const val TWO_CONSTANTS = "MVK_INVALID_COMPARE_TO_CONST"

private const val TOLERANCE_KIND = "MVK_INVALID_TYPE_FOR_TOLERANCE_OP"

private const val ORDERING_KIND = "MVK_INVALID_TYPE_FOR_COMPARISON"

private fun namesATemporalConstant(side: String, temporal: Set<String>): Boolean =
    tokenize(side).any { it.kind == TokenKind.NAME && it.text in temporal }

/**
 * What this side of the comparison is, or null when this reader will not say.
 *
 * Null is the common answer and the safe one: arithmetic, a nested call and an aggregate all carry a result
 * type A12 computes from the whole expression, so typing them from their most visible operand would be a
 * claim with nothing behind it.
 */
private fun PreparedModel.sideKindOf(
    side: String,
    declaringGroup: String,
    element: DeclaredElement,
): SideKind? {
    val tokens = tokenize(side)
    val digits = tokens.filter { it.kind == TokenKind.NUMBER }
    val separators = tokens.filterNot { it.kind == TokenKind.NUMBER }
    if (digits.isNotEmpty() && tokens.size <= 3 && separators.all { it.text == "." || it.text == "-" }) {
        return SideKind.NUMBER_CONSTANT
    }
    tokens.singleOrNull()?.takeIf { it.kind == TokenKind.STRING }?.let { return SideKind.OTHER_CONSTANT }
    // A side is a bare reference only when EVERY token belongs to one entity spec. Reducing by "it names
    // exactly one entity" instead would type `{[Qty] + 1}` from the `Qty` in it, which is the result type
    // A12 computes from the whole expression and this reader does not.
    if (tokens.any { it.kind !in REFERENCE_TOKENS || (it.kind == TokenKind.PUNCTUATION && it.text !in SPEC_PUNCTUATION) }) {
        return null
    }
    val reference = referencesIn(side, conditionLanguage).singleOrNull() ?: return null
    val resolved = resolveEntity(declaringGroup, reference.replace("*", ""), element.origin)
    val target = (resolved as? Reference.Resolved)?.path ?: return null
    val declared = elements.firstOrNull { it.path == target && it.declaredKind == DeclaredKind.FIELD }
        ?: return null
    return when (declared.baseTypeClass()) {
        BaseTypeClass.NUMBER -> SideKind.NUMBER_FIELD
        BaseTypeClass.DATE -> SideKind.DATE_FIELD
        null -> null
        else -> SideKind.OTHER_FIELD
    }
}
