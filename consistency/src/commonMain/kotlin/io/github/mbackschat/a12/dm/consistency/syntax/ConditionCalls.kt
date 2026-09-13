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
fun callsIn(text: String, language: ConditionLanguage, entry: SyntaxEntry): List<ConditionCall> {
    val keywords = CONDITION_KEYWORDS.getValue(language)
    return callShapes(text, language, entry).map { shape ->
        ConditionCall(shape.operator, shape.at, shape.operands.map { it.asOperand(keywords, text) })
    }
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
 * @param base the same reduction applied to the entity spec BEFORE the first clause, so an operand carrying
 *     one still says which element it is about. Equal to [path] on a clause-free operand, and absent on the
 *     same shapes [path] is absent for.
 * @param clauses what the spec carries, in authored order — empty on a bare operand.
 */
data class CallOperand(
    val path: String?,
    val base: String? = path,
    val clauses: List<OperandClause> = emptyList(),
)

/**
 * One clause hanging off an entity spec: a filter, a scope, or a semantic-index key.
 *
 * **Reading a clause is what lets a family see a shape it would otherwise have to stay silent on.** Until
 * this existed every clause-carrying operand reduced to nothing at all, so a law about the `@From` scope or
 * the `Having` filter had no operand to judge — [repetitionScopeGroupFindings] is the first consumer and
 * the reason this landed.
 *
 * @param keyword the clause keyword AS SPELLED, so a consumer matches it through [CONDITION_KEYWORDS] for
 *     the model's own language rather than against a literal that is a field name in the other one.
 * @param path the clause's own operand reduced by the same bare-spec rule as [CallOperand.path] — present
 *     for a scope, absent for a filter, whose operand is a whole condition rather than a path.
 * @param text the authored text of the clause's operand, for a consumer that needs to read it further.
 */
data class OperandClause(val keyword: String, val path: String?, val text: String)

/**
 * The operand this token run describes, split at its top-level clause keywords.
 *
 * Depth matters and is the whole subtlety: a `Having` filter's body may carry its own bracketed spec with
 * its own clause, and cutting there would attribute an inner clause to the outer operand. So the scan cuts
 * only where no delimiter is open.
 */
private fun List<Token>.asOperand(keywords: Map<String, KeywordKind>, source: String): CallOperand {
    val cuts = clauseCuts(keywords)
    if (cuts.isEmpty()) {
        return CallOperand(asPath(keywords))
    }
    val clauses = cuts.mapIndexed { position, cut ->
        val body = subList(cut.at + cut.width, cuts.getOrNull(position + 1)?.at ?: size)
        OperandClause(
            cut.keyword,
            body.asPath(keywords),
            if (body.isEmpty()) "" else source.substring(body.first().start, body.last().end),
        )
    }
    // `path` keeps its narrower contract deliberately: it has always meant "a bare spec and nothing else",
    // and widening it here would silently change every family already reading it.
    return CallOperand(null, subList(0, cuts.first().at).asPath(keywords), clauses)
}

/** Where one clause keyword sits, and how many tokens it spans. */
private class ClauseCut(val keyword: String, val at: Int, val width: Int)

/**
 * The top-level clause keywords in this run, in order.
 *
 * `@From` / `@Von` is the one that does not arrive as a single token — the tokenizer files `@` as
 * punctuation because it is also the `@*` marker — so it is matched as the adjacent pair, exactly as the
 * shape parser and the reference scan both do.
 */
private fun List<Token>.clauseCuts(keywords: Map<String, KeywordKind>): List<ClauseCut> {
    val cuts = mutableListOf<ClauseCut>()
    var depth = 0
    indices.forEach { at ->
        val token = this[at]
        when {
            token.kind == TokenKind.PUNCTUATION && token.text in OPENING_DELIMITERS -> depth++
            token.kind == TokenKind.PUNCTUATION && token.text in CLOSING_DELIMITERS -> depth--
            depth > 0 -> Unit
            token.kind == TokenKind.NAME && keywords[token.text] == KeywordKind.PATH_OP ->
                cuts += ClauseCut(token.text, at, 1)
            token.kind == TokenKind.PUNCTUATION && token.text == "@" -> {
                val name = getOrNull(at + 1)
                if (name != null && name.kind == TokenKind.NAME &&
                    keywords["@" + name.text] == KeywordKind.PATH_OP
                ) {
                    cuts += ClauseCut("@" + name.text, at, 2)
                }
            }
        }
    }
    return cuts
}

private val OPENING_DELIMITERS = setOf("(", "[")

private val CLOSING_DELIMITERS = setOf(")", "]")

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
