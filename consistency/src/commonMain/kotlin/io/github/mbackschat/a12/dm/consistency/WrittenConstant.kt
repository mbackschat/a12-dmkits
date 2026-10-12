package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.consistency.syntax.CONDITION_OPERATOR_KEYWORDS
import io.github.mbackschat.a12.dm.consistency.syntax.FALSE_CONSTANT
import io.github.mbackschat.a12.dm.consistency.syntax.NOW_CONSTANT
import io.github.mbackschat.a12.dm.consistency.syntax.TODAY_CONSTANT
import io.github.mbackschat.a12.dm.consistency.syntax.TRUE_CONSTANT
import io.github.mbackschat.a12.dm.consistency.syntax.TokenKind
import io.github.mbackschat.a12.dm.consistency.syntax.decodeAuthoredStringLiteral
import io.github.mbackschat.a12.dm.consistency.syntax.tokenize

/**
 * The operand type A12 gives one written-out side of a comparison.
 *
 * `CheckKonstanteImpl` assigns it before the comparison walk runs (source-read): a number at the scale it
 * spells, `True` and `False` as the two boolean constants, `Today` as a date of `dd.MM.yyyy` and `Now` as a
 * stamp, and a quoted literal as a DATE of the format its length names wherever `DateUtils.isStringAValidDate`
 * accepts it — as text otherwise. So a literal's date-ness is decided by its shape and the calendar, never by
 * what it is compared with: `"05.03.2024"` opposite a text field draws the date class, and `"29.02.2023"`
 * opposite a date draws what a text draws (`adapter.laws.CheckerComparisonConstantAgreementTest`,
 * [KERNEL-FINDINGS KF484]).
 *
 * `BaseYear` is a constant too, but of a base type with arms of its own, so it reads as no constant here; the
 * comparison walk resolves it by its partner instead ([isBaseYearConstant], [BaseYearRole]).
 */
internal sealed interface WrittenConstant {

    /** A written-out number, at the scale it spells. */
    data class Number(val scale: Int) : WrittenConstant

    /** `True` ([yes]) or `False`, which no arm admits opposite a date, a number or a text. */
    data class BooleanConstant(val yes: Boolean) : WrittenConstant

    /**
     * `Today`, `Now` or a date-shaped literal: a date at [components], with [keyword] telling the first two
     * apart, which is the one thing the two-constant gate asks of them.
     */
    data class Temporal(val components: Set<Component>, val keyword: Boolean) : WrittenConstant

    /** A quoted literal that is no date, carrying the text it spells. */
    data class Text(val value: String) : WrittenConstant
}

/** Whether [side] is the `BaseYear` terminal in the model's condition language. */
internal fun TrackedFactBase.isBaseYearConstant(side: String): Boolean {
    val single = tokenize(side).singleOrNull() ?: return false
    return single.text == CONDITION_OPERATOR_KEYWORDS[conditionLanguage]?.get("BaseYear")
}

/** What [side] is as a written-out constant, or null where it is none this reader types. */
internal fun TrackedFactBase.writtenConstantOf(side: String): WrittenConstant? {
    val tokens = tokenize(side)
    numberLiteralScale(tokens)?.let { return WrittenConstant.Number(it) }
    val single = tokens.singleOrNull() ?: return null
    if (single.kind == TokenKind.STRING) {
        val value = single.text.decodeAuthoredStringLiteral()
        return acceptedDateConstantFormat(value)
            ?.let { WrittenConstant.Temporal(componentsOf(it), keyword = false) }
            ?: WrittenConstant.Text(value)
    }
    val keywords = CONDITION_OPERATOR_KEYWORDS[conditionLanguage] ?: return null
    return when (single.text) {
        keywords[TRUE_CONSTANT] -> WrittenConstant.BooleanConstant(yes = true)
        keywords[FALSE_CONSTANT] -> WrittenConstant.BooleanConstant(yes = false)
        keywords[TODAY_CONSTANT] -> WrittenConstant.Temporal(componentsOf(DATE_FORMAT), keyword = true)
        keywords[NOW_CONSTANT] -> WrittenConstant.Temporal(componentsOf(STAMP_FORMAT), keyword = true)
        else -> null
    }
}

/**
 * Whether A12's two-constant gate lets [left] and [right] through to the walk behind it.
 *
 * `isValidConstantComparison` exempts a pair only where the LEFT constant is a date and exactly one of the two
 * is `Today` or `Now` (source-read), so `Today == 5` reaches the date arm while `5 == Today` and two date
 * literals are refused as two constants — measured in both orders
 * (`adapter.laws.CheckerComparisonConstantAgreementTest`).
 */
internal fun constantGateAdmits(left: WrittenConstant, right: WrittenConstant): Boolean =
    left is WrittenConstant.Temporal && left.keyword != ((right as? WrittenConstant.Temporal)?.keyword ?: false)
