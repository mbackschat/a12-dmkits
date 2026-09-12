package io.github.mbackschat.a12.dm.consistency.syntax

/**
 * Whether DSL text ends on something that still needs a right-hand side — A12's `MVK_INCOMPLETE_INPUT`.
 *
 * This is the second phase the checker can decide without a grammar, and the reason it can is narrow: the
 * answer depends only on the LAST significant token, never on the structure before it. A12 reports this code
 * when it runs out of input, which measurably happens for a trailing infix operator, a trailing comparison or
 * arithmetic symbol, and a trailing comma ([KERNEL-FINDINGS KF300]).
 *
 * **It fires only where the text is delimiter-BALANCED, and that restriction replaces a refuted mechanism.**
 * This check originally keyed on the operator catalog's `OPERATOR`/`PATH_OP` split, on the reading that a
 * trailing `PATH_OP` draws `MVK_UNEXPECTED_TOKEN` while a trailing `OPERATOR` draws `MVK_INCOMPLETE_INPUT`.
 * That was measured on one table member and is false in both directions: `FieldFilled(Tier For` — the same
 * keyword with one bracket removed — draws `MVK_INCOMPLETE_INPUT`, and `FieldFilled(Tier And` draws
 * `MVK_UNEXPECTED_TOKEN`. What both carry is an unclosed delimiter EARLIER in the text, so the last token
 * cannot decide the code at all and no grammar-free rule can.
 *
 * So this is deliberately not a mechanism but a boundary around the measured region: every row A12 answers
 * with `MVK_INCOMPLETE_INPUT` for a trailing operator has every delimiter closed, and every row carrying an
 * unclosed one is answered differently. Outside that region the family stays silent, which under-reports
 * broken text — the only safe direction, and the report's gap already says the grammar is not covered.
 */
fun incompleteInput(text: String, language: ConditionLanguage): IncompleteInput? {
    val keywords = CONDITION_KEYWORDS.getValue(language)
    // Whitespace never becomes a token, and a comment is not input the grammar consumes — so the last
    // SIGNIFICANT token is what A12 is left holding, and `… And ;; note` ends on `And` just as `… And ` does.
    val significant = tokenize(text).filter { it.kind != TokenKind.COMMENT }
    val last = significant.lastOrNull() ?: return null
    val dangling = when (last.kind) {
        TokenKind.NAME -> keywords[last.text] == KeywordKind.OPERATOR && significant.everyDelimiterClosed()
        TokenKind.OPERATOR_SYMBOL -> significant.everyDelimiterClosed()
        // A trailing comma is measured on its own and is never balanced by nature — it can only sit inside an
        // argument list that is still open, so requiring closure here would delete the case rather than scope it.
        TokenKind.PUNCTUATION -> last.text == ","
        else -> false
    }
    return if (dangling) IncompleteInput(last.text, last.start) else null
}

/**
 * Whether every `(` and `[` in these tokens is closed by its own kind.
 *
 * The kinds are matched rather than counted because a counter calls `FieldFilled(Tier] And` balanced, and A12
 * answers the mismatch with `MVK_UNEXPECTED_TOKEN` — so counting would put a row A12 reports differently back
 * inside the region this function exists to draw.
 */
private fun List<Token>.everyDelimiterClosed(): Boolean {
    val open = ArrayDeque<String>()
    for (token in this) {
        if (token.kind != TokenKind.PUNCTUATION) continue
        when (token.text) {
            "(", "[" -> open.addLast(token.text)
            ")" -> if (open.removeLastOrNull() != "(") return false
            "]" -> if (open.removeLastOrNull() != "[") return false
        }
    }
    return open.isEmpty()
}

/**
 * The token the text ended on, and where.
 *
 * [spelling] is carried rather than re-derived because the author wrote it in their own condition language,
 * and echoing their spelling back is what makes the finding locatable in a long condition.
 */
data class IncompleteInput(val spelling: String, val at: Int)
