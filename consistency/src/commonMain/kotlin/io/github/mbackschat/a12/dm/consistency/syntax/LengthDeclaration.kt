package io.github.mbackschat.a12.dm.consistency.syntax

/**
 * Whether a condition is the ONE `Length` shape A12 admits over a raw-type field, and the constant it names.
 *
 * A12's gate (`ParserService.checkNormalRule`) admits `Length(f) > c` and its mirror `c < Length(f)` as the
 * WHOLE rule condition, and nothing else — the shape is not a rule at all but a disguised max-length
 * declaration, lifted out of the generated validation ([KERNEL-FINDINGS KF35],
 * `rulekit.validate.laws.NvvRawTypeLawsTest`).
 *
 * **Redundant parentheses are transparent to the gate, and that row decides this reader.** `(Length(f) > 5)`
 * is ADMITTED, so matching the accepted layout against the raw token stream would refuse a model A12 accepts
 * — the one direction a checker may not take. Measured 2026-09-13 rather than inferred from the accepted
 * set, together with the four layouts a real author writes that the accepted set does not reach: both
 * comparisons reversed, `Or` beside the already-measured `And`, and a non-strict comparator.
 *
 * @return the constant AS WRITTEN — its domain is a separate gate that A12 reaches only once this one
 *     passes, so reading the two apart is what lets a consumer report the code A12 would have reached.
 */
internal fun maxLengthDeclaration(text: String, lengthSpelling: String): String? {
    val tokens = tokenize(text).filter { it.kind != TokenKind.COMMENT }.stripRedundantParentheses()
    return tokens.asLengthGreaterThan(lengthSpelling) ?: tokens.asConstantLessThanLength(lengthSpelling)
}

/** `Length ( … ) > <constant>` — the operator first, the constant running to the end. */
private fun List<Token>.asLengthGreaterThan(lengthSpelling: String): String? {
    if (size < 5 || this[0].text != lengthSpelling || this[1].text != "(") {
        return null
    }
    val closed = matchingClose(1) ?: return null
    return if (getOrNull(closed + 1)?.text == ">") constantOf(closed + 2, size) else null
}

/** `<constant> < Length ( … )` — the mirror, whose call must run to the very end. */
private fun List<Token>.asConstantLessThanLength(lengthSpelling: String): String? {
    if (size < 5 || last().text != ")") {
        return null
    }
    val opened = matchingOpen(lastIndex) ?: return null
    return if (opened >= 3 && this[opened - 1].text == lengthSpelling && this[opened - 2].text == "<") {
        constantOf(0, opened - 2)
    } else {
        null
    }
}

/**
 * The numeric literal spelled by `[from, until)`, or null when the run is anything else.
 *
 * Assembled from token TEXT rather than read off one token, because the lexer splits a decimal into its
 * digit runs and its point — so `5.0` is three tokens and the whole-condition test must still see one
 * constant. A run carrying anything that is not a digit sequence, a point or a sign is not a constant at
 * all, which is how a larger condition (`… > 5 And FieldFilled(x)`) fails the shape rather than the domain.
 */
private fun List<Token>.constantOf(from: Int, until: Int): String? {
    if (from >= until) {
        return null
    }
    val run = subList(from, until)
    if (run.any { it.kind != TokenKind.NUMBER && it.text != "." && it.text != "-" }) {
        return null
    }
    return run.joinToString("") { it.text }
}

/**
 * The same tokens with each fully-enclosing parenthesis pair removed.
 *
 * Only a pair that spans the WHOLE run: `(a) > (b)` opens and closes with parentheses that are not each
 * other's match, and stripping them would read two different expressions as one.
 */
private fun List<Token>.stripRedundantParentheses(): List<Token> {
    var tokens = this
    while (tokens.size >= 2 && tokens.first().text == "(" && tokens.matchingClose(0) == tokens.lastIndex) {
        tokens = tokens.subList(1, tokens.lastIndex)
    }
    return tokens
}

private fun List<Token>.matchingClose(open: Int): Int? {
    var depth = 0
    (open until size).forEach { at ->
        when (this[at].text) {
            "(" -> depth++
            ")" -> if (--depth == 0) return at
        }
    }
    return null
}

private fun List<Token>.matchingOpen(close: Int): Int? {
    var depth = 0
    (close downTo 0).forEach { at ->
        when (this[at].text) {
            ")" -> depth++
            "(" -> if (--depth == 0) return at
        }
    }
    return null
}
