package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.consistency.syntax.CONDITION_OPERATOR_KEYWORDS
import io.github.mbackschat.a12.dm.consistency.syntax.ConditionComparison
import io.github.mbackschat.a12.dm.consistency.syntax.TokenKind
import io.github.mbackschat.a12.dm.consistency.syntax.comparisonsIn
import io.github.mbackschat.a12.dm.consistency.syntax.tokenize
import io.github.mbackschat.a12.dm.predicates.kernelPatternBlacklist

/**
 * What a PATTERN comparison demands of its two sides, in the order A12 asks.
 *
 * `CheckVergleichsBedingungImpl.checkPatternOp` returns before every type-agreement arm and asks four
 * questions of its own ([KERNEL-FINDINGS KF387],
 * `rulekit.validate.laws.PatternComparisonOperandLawsTest`).
 *
 * | the question | class |
 * |---|---|
 * | is the left side a plain FIELD READ? | `MVK_INVALID_PARAMETER_FOR_PATTERN_COMPARISON` |
 * | is each side a TEXT declaration or a written-out text? | `MVK_INVALID_TYPE_FOR_PATTERN_COMPARISON` |
 * | is the right side WRITTEN OUT? | `MVK_MISSING_CONST_IN_PATTERN_COMP` |
 * | is that written-out value a pattern the admission gate takes? | `MVK_INVALID_PATTERN` |
 *
 * **The first question is about SHAPE, not type**, which is what separates the first two classes: a
 * written-out value on the left is refused for being an expression rather than for what it spells, and a
 * call is refused there before its result type is ever asked. Measured on both.
 *
 * **The third question is asked after BOTH type questions**, so a number declaration on the right draws the
 * type class while a TEXT declaration draws the constant one — the pair that shows the order, since a
 * reading that asked about constancy first would name one class for both.
 *
 * **The pattern gate is the same admission gate a DECLARED pattern meets, under a different code.** The
 * reader is shared with [declaredPatternFindings]; the codes are not, and the difference is two words
 * swapped rather than anything about the check ([KF165]). Its Java-regex half is unanswerable off the JVM
 * and is declared as a coverage gap there, which covers this carrier too.
 *
 * **Silent where the left side is a bare name that does not RESOLVE**: that is the reference family's
 * answer, and it runs before this walk. Silent too on a `CustomFieldType` declaration, whose base type is not
 * measured on this carrier, and wherever both sides are written out — the constant gate answers that pair
 * ([comparisonOperatorOperandFindings]).
 */
internal fun TrackedFactBase.patternComparisonOperandFindings(): List<Finding> {
    val keywords = CONDITION_OPERATOR_KEYWORDS[conditionLanguage] ?: return emptyList()
    val operators = PATTERN_OPERATORS.mapNotNull { keywords[it] }.toSet()
    if (operators.isEmpty()) {
        return emptyList()
    }
    return declarations().mapNotNull { element ->
        val body = element.body ?: return@mapNotNull null
        val group = element.path.substringBeforeLast('/', missingDelimiterValue = "")
        conditionTexts(element.kind, body).firstNotNullOfOrNull { (member, text) ->
            comparisonsIn(text, conditionLanguage, syntaxEntryOf(member))
                .filter { it.operator in operators }
                .firstNotNullOfOrNull { comparison -> patternFinding(comparison, group, element, member) }
        }
    }
}

/** The two pattern comparators, by catalog id — the arm that takes a field and a written-out regex. */
private val PATTERN_OPERATORS = listOf("PatternMatched", "PatternViolated")

private const val SHAPE = "MVK_INVALID_PARAMETER_FOR_PATTERN_COMPARISON"

private const val KIND = "MVK_INVALID_TYPE_FOR_PATTERN_COMPARISON"

private const val NOT_CONSTANT = "MVK_MISSING_CONST_IN_PATTERN_COMP"

private const val BAD_PATTERN = "MVK_INVALID_PATTERN"

/** What one side is, as far as the four questions need to know. */
private sealed interface PatternSide {

    /** A written-out value, carrying the text it spells when that text is a string literal. */
    data class Written(val text: String?) : PatternSide

    /** An expression or a call — a shape the left side may not take, whatever it evaluates to. */
    data object Expression : PatternSide

    /** A declaration, at the `fieldType` discriminator it names. */
    data class Declared(val path: String, val type: FieldType) : PatternSide
}

/** The class this comparison fails on, the side that fails it, and why — where the class carries a why. */
private data class PatternDefect(val code: String, val wrote: String, val listed: String? = null)

private fun TrackedFactBase.patternFinding(
    comparison: ConditionComparison,
    declaringGroup: String,
    element: TrackedDeclaration,
    member: String,
): Finding? {
    val left = sideOf(comparison.left, declaringGroup, element, comparison.inAFilterBody) ?: return null
    val right = sideOf(comparison.right, declaringGroup, element, comparison.inAFilterBody) ?: return null
    // Two written-out values are the constant gate's, which is asked ahead of this whole arm.
    if (left is PatternSide.Written && right is PatternSide.Written) {
        return null
    }
    val defect = defectOf(comparison, left, right) ?: return null
    return Finding(
        FindingCategory.A12_CONSTRAINT,
        FindingSeverity.ERROR,
        defect.code,
        when (defect.code) {
            SHAPE ->
                "in `$member`: `${comparison.operator}` reads ONE declaration's value against a pattern, " +
                    "and `${defect.wrote}` is not a plain read — put the field on the left and the " +
                    "pattern on the right"
            KIND ->
                "in `$member`: `${comparison.operator}` matches text against a pattern, and " +
                    "`${defect.wrote}` is not text — match a text declaration against a written-out pattern"
            NOT_CONSTANT ->
                "in `$member`: `${comparison.operator}` takes its pattern as a written-out value, and " +
                    "`${defect.wrote}` is a declaration — A12 reads the pattern while it checks the " +
                    "model, so it cannot come from a document"
            else ->
                "in `$member`: the pattern `${defect.wrote}` carries ${defect.listed}, which A12's " +
                    "admission gate refuses. The gate reads the pattern's SOURCE TEXT rather than the " +
                    "expression it spells, so escaping the character does not exempt it"
        },
        element.path,
        comparison.spanIn(member),
    )
}

/** The first question this pair fails, in A12's own order, or null when it passes all four. */
private fun defectOf(
    comparison: ConditionComparison,
    left: PatternSide,
    right: PatternSide,
): PatternDefect? {
    if (left !is PatternSide.Declared) {
        return PatternDefect(SHAPE, comparison.left)
    }
    if (left.type != FieldType.STRING) {
        return PatternDefect(KIND, comparison.left)
    }
    val written = when (right) {
        is PatternSide.Declared -> if (right.type != FieldType.STRING) {
            return PatternDefect(KIND, comparison.right)
        } else {
            return PatternDefect(NOT_CONSTANT, comparison.right)
        }
        is PatternSide.Expression -> return null
        is PatternSide.Written -> right.text ?: return PatternDefect(KIND, comparison.right)
    }
    val listed = kernelPatternBlacklist(written) ?: return null
    return PatternDefect(BAD_PATTERN, comparison.right, listed)
}

/** The token kinds one entity spec may be made of; anything else makes the side an expression. */
private val SPEC_TOKENS = setOf(TokenKind.NAME, TokenKind.QUOTED_NAME, TokenKind.PUNCTUATION)

/** The punctuation an entity spec may carry: its brackets, its path steps, and a repetition star. */
private val SPEC_PUNCTUATION = setOf("[", "]", "/", "*", ".")

/**
 * What this side is, or null wherever this reader will not say.
 *
 * The null case is narrow and load-bearing: a side spelled as ONE entity spec that does not resolve belongs
 * to the reference family, which answers before this walk — reporting the shape class on it would name this
 * arm's class on a model A12 refuses for a missing declaration.
 */
private fun TrackedFactBase.sideOf(
    side: String,
    declaringGroup: String,
    element: TrackedDeclaration,
    inAFilterBody: Boolean,
): PatternSide? {
    val tokens = tokenize(side)
    val single = tokens.singleOrNull()
    if (single?.kind == TokenKind.STRING) {
        return PatternSide.Written(single.text.removeSurrounding("\"").replace("\"\"", "\""))
    }
    if (isNumberLiteral(tokens)) {
        return PatternSide.Written(null)
    }
    val outsideASpec = tokens.any {
        it.kind !in SPEC_TOKENS || (it.kind == TokenKind.PUNCTUATION && it.text !in SPEC_PUNCTUATION)
    }
    if (tokens.isEmpty() || outsideASpec) {
        return PatternSide.Expression
    }
    val declared = bareReferenceField(side, declaringGroup, element, inAFilterBody) ?: return null
    val type = declared.declaredFieldType() ?: return null
    return if (type in UNMEASURED_TYPES) null else PatternSide.Declared(declared.path, type)
}

/** The declarations whose base type this reader does not know on this carrier, so no question judges them. */
private val UNMEASURED_TYPES = setOf(FieldType.CUSTOM_FIELD, FieldType.TYPE_DEF, FieldType.UNSPECIFIED)
