package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.consistency.syntax.ConditionComparison
import io.github.mbackschat.a12.dm.consistency.syntax.TokenKind
import io.github.mbackschat.a12.dm.consistency.syntax.comparisonsIn
import io.github.mbackschat.a12.dm.consistency.syntax.tokenize

/**
 * What a TEXT or ENUMERATION side of a comparison needs of its partner, and which written-out values an
 * enumeration admits.
 *
 * `CheckVergleichsBedingungImpl.checkStringOrEnumType` runs TWICE, once per side, after the date, date-range
 * and boolean arms. It asks two separable questions, and the separation is what lets this family report both
 * without touching the display-value machinery between them
 * ([KERNEL-FINDINGS KF384], `rulekit.validate.laws.StringEnumComparisonAgreementLawsTest`).
 *
 * | the question | class |
 * |---|---|
 * | is a text or enumeration side's partner a NUMBER? | `MVK_INVALID_COMPARE_TO_ENUM_OR_STRING` |
 * | is a written-out value opposite an enumeration one of its declared values? | `MVK_INVALID_STRING_CONSTANT_FOR_ENUM_COMPARISON` |
 *
 * **Because the walk runs once per side, the TEXT side raises the kind class wherever it sits** — a number
 * declaration against a written-out text draws it as surely as a text declaration against a written-out
 * number, measured in both spellings.
 *
 * **The domain question reads the STORED values and never the display ones.** An enumeration declaring
 * `BASIC` with the label `Basic plan` refuses `"Basic plan"` and admits `"BASIC"`, which is the row that
 * separates the two readings a labelled enumeration could carry.
 *
 * **The DISPLAY-VALUE half of the same walk is [enumDisplayComparabilityFindings]'**, and the split is the
 * measurement rather than a convenience: `MVK_INVALID_COMPARE_ENUM_TO_STRING` and
 * `MVK_INVALID_COMPARE_ENUM_TO_ENUM` compare what two declarations LABEL their values, and they are
 * reachable only where both sides are text-or-enum — exactly the region the kind question leaves admitted.
 * The two halves therefore never contend, measured on a display-bearing enumeration against a text field and
 * against a textless enumeration. Their question is also about the PAIR, which this per-side walk could not
 * carry: the same enumeration is fine opposite one partner and refused opposite another.
 *
 * **Silent on a `CustomFieldType` declaration.** It shares this checker's TEXT class with `StringType`, and
 * whether A12 gives it the same base type is not measured on this carrier — assuming it would report the
 * kind class on a comparison A12 may well admit.
 *
 * **Silent on a categorized reference** (`Tier -> Band`), whose domain is the category's tokens rather than
 * the enum's values: the shared side reader does not reduce that spelling, and widening it is a change to
 * every family that reads an operand. A12 answers such a comparison with the same class, measured by
 * [categoryReferenceFindings]' grid.
 *
 * **Only `==` and `!=` are judged.** A text or enumeration side cannot survive the ordering gate, and the
 * tolerance and pattern arms return before this walk — so equality is the whole reachable population.
 */
fun PreparedModel.stringEnumComparisonAgreementFindings(): List<Finding> = elements.mapNotNull { element ->
    val body = element.body ?: return@mapNotNull null
    val group = element.path.substringBeforeLast('/', missingDelimiterValue = "")
    conditionTexts(element.kind, body).firstNotNullOfOrNull { (member, text) ->
        comparisonsIn(text, conditionLanguage, syntaxEntryOf(member))
            .firstNotNullOfOrNull { comparison -> agreementFinding(comparison, group, element, member) }
    }
}

private const val EQUAL = "=="

private const val NOT_EQUAL = "!="

private const val WRONG_KIND = "MVK_INVALID_COMPARE_TO_ENUM_OR_STRING"

private const val OUTSIDE_DOMAIN = "MVK_INVALID_STRING_CONSTANT_FOR_ENUM_COMPARISON"

/**
 * What one side is, as far as the two questions need to know.
 *
 * [written] is load-bearing rather than descriptive: the constant gate ahead of this walk answers a pair
 * with no declaration on either side, so a family that judged one would name a second class on a model A12
 * already refuses.
 */
private sealed interface TextSide {

    val written: Boolean

    /** A `StringType` declaration. */
    data object TextField : TextSide {
        override val written = false
    }

    /** A number declaration — the partner both questions refuse. */
    data object NumberField : TextSide {
        override val written = false
    }

    /** A written-out number, which the same questions refuse the same way. */
    data object NumberConstant : TextSide {
        override val written = true
    }

    /** A written-out text, carrying the value it spells. */
    data class TextConstant(val value: String) : TextSide {
        override val written = true
    }

    /** An `EnumerationType` declaration, carrying the values it declares. */
    data class EnumField(val path: String, val values: List<String>) : TextSide {
        override val written = false
    }
}

private val TextSide.isTextual: Boolean
    get() = this is TextSide.TextField || this is TextSide.EnumField || this is TextSide.TextConstant

private val TextSide.isNumberLike: Boolean
    get() = this === TextSide.NumberField || this === TextSide.NumberConstant

private fun PreparedModel.agreementFinding(
    comparison: ConditionComparison,
    declaringGroup: String,
    element: DeclaredElement,
    member: String,
): Finding? {
    if (comparison.operator != EQUAL && comparison.operator != NOT_EQUAL) {
        return null
    }
    val left = sideOf(comparison.left, declaringGroup, element) ?: return null
    val right = sideOf(comparison.right, declaringGroup, element) ?: return null
    if (left.written && right.written) {
        return null
    }
    wrongKindFinding(left, right, comparison, element, member)?.let { return it }
    val enumeration = (left as? TextSide.EnumField) ?: (right as? TextSide.EnumField) ?: return null
    val constant = (if (left === enumeration) right else left) as? TextSide.TextConstant ?: return null
    if (constant.value in enumeration.values) {
        return null
    }
    return Finding(
        FindingCategory.A12_CONSTRAINT,
        FindingSeverity.ERROR,
        OUTSIDE_DOMAIN,
        "in `$member`: `${enumeration.path}` declares " + enumeration.values.joinToString { "`$it`" } +
            ", and `${constant.value}` is none of them — compare against a declared value; a label an " +
            "Enumeration shows for a value is not one of them",
        element.path,
    )
}

/** The kind class where one side is text or enumeration and the other a number, or null. */
private fun wrongKindFinding(
    left: TextSide,
    right: TextSide,
    comparison: ConditionComparison,
    element: DeclaredElement,
    member: String,
): Finding? {
    val textual = when {
        left.isTextual -> left
        right.isTextual -> right
        else -> return null
    }
    val partner = if (left === textual) right else left
    if (!partner.isNumberLike) {
        return null
    }
    val spoken = if (textual is TextSide.EnumField) "an enumeration" else "text"
    val wrote = if (left === textual) comparison.right else comparison.left
    return Finding(
        FindingCategory.A12_CONSTRAINT,
        FindingSeverity.ERROR,
        WRONG_KIND,
        "in `$member`: one side of this comparison is $spoken, so the other must be text or an " +
            "enumeration too — `$wrote` is a number; compare a number against a number, or put the " +
            "number's own field on both sides",
        element.path,
    )
}

/** What this side is, or null wherever this reader will not say — see [bareReferenceField]. */
private fun PreparedModel.sideOf(
    side: String,
    declaringGroup: String,
    element: DeclaredElement,
): TextSide? {
    val tokens = tokenize(side)
    if (isNumberLiteral(tokens)) {
        return TextSide.NumberConstant
    }
    tokens.singleOrNull()?.takeIf { it.kind == TokenKind.STRING }?.let {
        return TextSide.TextConstant(it.text.removeSurrounding("\"").replace("\"\"", "\""))
    }
    val declared = bareReferenceField(side, declaringGroup, element) ?: return null
    return when (declared.declaredFieldType()) {
        "StringType" -> TextSide.TextField
        "NumberType" -> TextSide.NumberField
        "EnumerationType" -> declared.enumerationBody()?.declaredEnumValues()
            ?.let { TextSide.EnumField(declared.path, it) }
        else -> null
    }
}
