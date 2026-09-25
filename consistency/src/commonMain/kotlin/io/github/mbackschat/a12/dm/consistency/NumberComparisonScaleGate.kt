package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.consistency.syntax.CONDITION_OPERATOR_KEYWORDS
import io.github.mbackschat.a12.dm.consistency.syntax.ConditionComparison
import io.github.mbackschat.a12.dm.consistency.syntax.comparisonsIn
import io.github.mbackschat.a12.dm.consistency.syntax.tokenize
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * What EQUALITY demands of two numbers' declared SCALES, and what a number may stand against at all.
 *
 * `CheckVergleichsBedingungImpl.checkNumberComparison` is the last arm of the comparison walk and carries two
 * classes ([KERNEL-FINDINGS KF385], `rulekit.validate.laws.NumberComparisonScaleGateLawsTest`).
 *
 * | the question | class |
 * |---|---|
 * | do `==` / `!=` join two numbers of the same fractional-digit count? | `MVK_INVALID_COMPARE_DEC_PLACES` |
 * | is a number's partner number-like at all? | `MVK_INCONSISTENT_TYPES_COMPARED` |
 *
 * **A WRITTEN-OUT number is the one operand A12 will rescale, and only in one direction**, which is the law
 * rather than a detail: a differing pair is admitted exactly when the SMALLER-scale side is the literal. So
 * a scale-2 declaration admits `1` and a scale-0 one refuses `1.00` — the same pairing of a declaration with
 * a literal of a different scale, answered oppositely, measured in both spellings.
 *
 * **A declaration omitting `maxFractionalDigits` carries scale ZERO**, measured rather than defaulted: it is
 * refused against a scale-2 declaration and against `1.00`, and admitted against `1`.
 *
 * **The scale gate is the one comparison class a COMPUTATION may switch off for itself.** A12 reads
 * `errorCodesToSuppress` into an inline pragma the parser honours, so a computation naming this code is left
 * alone here — measured with the unsuppressed computation as the control that locates the refusal. No other
 * carrier can suppress it, and no other class can be suppressed ([suppressedWarningFindings]).
 *
 * **Ordering, tolerance and pattern comparisons never reach either question**, each measured: ordering
 * compares magnitudes and needs no common scale, while the tolerance arm RETURNS once both sides are
 * numbers. Only `==` and `!=` are judged.
 *
 * **The wrong-type class is reachable only through a written-out BOOLEAN constant.** Every declaration that
 * is not number-like is answered earlier — a date by `MVK_INVALID_COMPARE_TO_DATE`, a boolean by the yes/no
 * arm, text and enumerations by [stringEnumComparisonAgreementFindings] — so `True` and `False` are what is
 * left, and a boolean declaration on the same shape is measured to draw the other class.
 *
 * **Silent wherever a side is not a bare reference or a written-out number**, for the reason
 * [bareReferenceField] carries: an arithmetic side's scale is one A12 derives from the whole expression, and
 * it also carries the multiplicative-constant capability that relaxes this very gate.
 */
internal fun TrackedFactBase.numberComparisonScaleFindings(): List<Finding> = declarations().mapNotNull { element ->
    val body = element.body ?: return@mapNotNull null
    val group = element.path.substringBeforeLast('/', missingDelimiterValue = "")
    val suppressed = body.suppressesTheScaleGate()
    conditionTexts(element.kind, body).firstNotNullOfOrNull { (member, text) ->
        comparisonsIn(text, conditionLanguage, syntaxEntryOf(member))
            .firstNotNullOfOrNull { comparison ->
                scaleFinding(comparison, suppressed, group, element, member)
            }
    }
}

private const val EQUAL = "=="

private const val NOT_EQUAL = "!="

private const val WRONG_TYPE = "MVK_INCONSISTENT_TYPES_COMPARED"

/** The two constants A12 spells as bare NAMES, which no arm of this walk admits opposite a number. */
private val BOOLEAN_CONSTANTS = listOf("True", "False")

/** What one side is, as far as the two questions need to know. */
private sealed interface NumberSide {

    /** A written-out number — the one operand A12 will rescale, and the reason a pair may differ. */
    data class Written(val scale: Int) : NumberSide

    /** A number declaration, at the scale it declares. */
    data class Declared(val path: String, val scale: Int) : NumberSide

    /** `True` or `False`, which is not number-like however it is written. */
    data object BooleanConstant : NumberSide
}

private fun TrackedFactBase.scaleFinding(
    comparison: ConditionComparison,
    suppressed: Boolean,
    declaringGroup: String,
    element: TrackedDeclaration,
    member: String,
): Finding? {
    if (comparison.operator != EQUAL && comparison.operator != NOT_EQUAL) {
        return null
    }
    val left = sideOf(comparison.left, declaringGroup, element, comparison.filteredBy) ?: return null
    val right = sideOf(comparison.right, declaringGroup, element, comparison.filteredBy) ?: return null
    wrongTypeFinding(left, right, comparison, element, member)?.let { return it }
    if (suppressed) {
        return null
    }
    val leftScale = (left as? NumberSide.Declared)?.scale ?: (left as? NumberSide.Written)?.scale
        ?: return null
    val rightScale = (right as? NumberSide.Declared)?.scale ?: (right as? NumberSide.Written)?.scale
        ?: return null
    // Two written-out values are the constant gate's, and equal scales are admitted whatever the sides are.
    if (leftScale == rightScale || (left is NumberSide.Written && right is NumberSide.Written)) {
        return null
    }
    val smaller = if (leftScale < rightScale) left else right
    if (smaller is NumberSide.Written) {
        return null
    }
    return Finding(
        FindingCategory.A12_CONSTRAINT,
        FindingSeverity.ERROR,
        SUPPRESSIBLE,
        "in `$member`: `${comparison.left}` carries $leftScale fractional digits and " +
            "`${comparison.right}` carries $rightScale, and `${comparison.operator}` compares them digit " +
            "for digit — round one side to the other's scale, or compare with `<` / `>`, which needs no " +
            "common scale",
        element.path,
        comparison.spanIn(member),
    )
}

/** A number opposite a written-out boolean constant, or null. */
private fun wrongTypeFinding(
    left: NumberSide,
    right: NumberSide,
    comparison: ConditionComparison,
    element: TrackedDeclaration,
    member: String,
): Finding? {
    val declared = (left as? NumberSide.Declared) ?: (right as? NumberSide.Declared) ?: return null
    val partner = if (left === declared) right else left
    if (partner !== NumberSide.BooleanConstant) {
        return null
    }
    val wrote = if (left === declared) comparison.right else comparison.left
    return Finding(
        FindingCategory.A12_CONSTRAINT,
        FindingSeverity.ERROR,
        WRONG_TYPE,
        "in `$member`: `${declared.path}` is a number and `$wrote` is a boolean constant, so the two " +
            "have no common value to compare — compare the number against a number, or test the boolean " +
            "declaration this constant belongs to",
        element.path,
        comparison.spanIn(member),
    )
}

/** Whether this declaration names the scale gate in `errorCodesToSuppress` — the one class A12 lets it. */
private fun JsonObject.suppressesTheScaleGate(): Boolean =
    (this["errorCodesToSuppress"] as? JsonArray)
        ?.any { (it as? JsonPrimitive)?.content == SUPPRESSIBLE } == true

/** What this side is, or null wherever this reader will not say — see [bareReferenceField]. */
private fun TrackedFactBase.sideOf(
    side: String,
    declaringGroup: String,
    element: TrackedDeclaration,
    filteredBy: String?,
): NumberSide? {
    val tokens = tokenize(side)
    numberLiteralScale(tokens)?.let { return NumberSide.Written(it) }
    val keywords = CONDITION_OPERATOR_KEYWORDS[conditionLanguage]
    val spellings = BOOLEAN_CONSTANTS.mapNotNull { keywords?.get(it) }
    tokens.singleOrNull()?.takeIf { it.text in spellings }?.let { return NumberSide.BooleanConstant }
    val declared = bareReferenceField(side, declaringGroup, element, filteredBy) ?: return null
    if (declared.declaredFieldType() != FieldType.NUMBER) {
        return null
    }
    return NumberSide.Declared(declared.path, declared.declaredScale())
}

/** The fractional digits this declaration carries; an omitted member is zero, measured rather than assumed. */
private fun TrackedDeclaration.declaredScale(): Int =
    ((FieldType.NUMBER.bodyIn(body?.get("fieldType") as? JsonObject))
        ?.get("maxFractionalDigits") as? JsonPrimitive)?.intOrNull ?: 0
