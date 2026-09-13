package io.github.mbackschat.a12.dm.consistency.syntax

/**
 * The operator calls a condition or operation text makes, each with its own operand list.
 *
 * [referencesIn] answers what a text NAMES and deliberately loses where each name stood; this answers where
 * they stood. The distinction is load-bearing for any law about one operator's argument list: a group named
 * once in each of two calls is not the same group named twice in one, and A12 admits the first and refuses
 * the second (`rulekit.validate.laws.GroupListDuplicateReachLawsTest`). No scan over a flat set of names can
 * tell those apart, which is why this parses.
 *
 * A text that does not parse yields no calls at all rather than the calls it managed to read. The grammar
 * family already reports such a text, and a truncated argument list is a SHORTER list — the shape that makes
 * an operand law look satisfied.
 */
fun callsIn(text: String, language: ConditionLanguage, entry: SyntaxEntry): List<ConditionCall> =
    callShapes(text, language, entry).map { shape ->
        ConditionCall(
            shape.operator,
            shape.at,
            shape.operands.map { CallOperand(it.asPath(CONDITION_KEYWORDS.getValue(language))) },
        )
    }

/**
 * One call: the operator as it was SPELLED, and its top-level arguments in authored order.
 *
 * The spelling rather than the catalog id, because that is what the text carries; a consumer that names an
 * operator resolves its id through [CONDITION_OPERATOR_KEYWORDS] for the model's own language.
 *
 * @param at the offset of the operator name.
 */
data class ConditionCall(val operator: String, val at: Int, val operands: List<CallOperand>)

/**
 * One top-level argument.
 *
 * @param path the entity path it names, in the same normalized spelling [referencesIn] produces and with any
 *     repetition wildcard kept — absent when the argument is anything else: a literal, an arithmetic
 *     expression, a nested call, or a reference carrying a filter, a scope or a category selector. Absent is
 *     never "names nothing"; it is "this reader did not reduce it to one path", so a law over operands must
 *     treat it as an operand it cannot compare rather than one that is not there.
 */
data class CallOperand(val path: String?)

/**
 * The path this token run names, or null when the run is not a bare entity spec.
 *
 * Bare means the run is path syntax and nothing else, so a clause keyword (`Having`, `@From`, `->`), a
 * bracket, an operator symbol or a literal all disqualify it — each of those makes the argument something
 * whose identity is not the path inside it. The `$` outer-repetition marker disqualifies it too: the marker
 * selects a different repetition of the same element, which is a distinction paths do not carry.
 */
private fun List<Token>.asPath(keywords: Map<String, KeywordKind>): String? {
    var names = 0
    forEach { token ->
        when {
            token.kind == TokenKind.NAME && token.text in keywords -> return null
            token.isName() -> names++
            token.kind == TokenKind.NUMBER || token.text in PATH_PUNCTUATION -> Unit
            else -> return null
        }
    }
    return if (names > 0) normalizedPath(this) else null
}
