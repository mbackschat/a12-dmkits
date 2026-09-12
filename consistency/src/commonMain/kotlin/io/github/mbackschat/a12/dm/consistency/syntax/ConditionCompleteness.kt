package io.github.mbackschat.a12.dm.consistency.syntax

/**
 * Whether DSL text ends on something that still needs a right-hand side — A12's `MVK_INCOMPLETE_INPUT`.
 *
 * This is the second phase the checker can decide without a grammar, and the reason it can is narrow: the
 * answer depends only on the LAST significant token, never on the structure before it. A12 reports this code
 * when it runs out of input, which measurably happens for a trailing infix operator, a trailing comparison or
 * arithmetic symbol, and a trailing comma ([KERNEL-FINDINGS KF300]).
 *
 * **The class stops at the operator catalog's kind boundary, and that boundary is measured.** A dangling PATH
 * operator — `->`, `For` — draws `MVK_UNEXPECTED_TOKEN` instead, because A12 meets a token it cannot use
 * rather than running out of input. So [KeywordKind.PATH_OP] is deliberately absent below, and `->` is
 * excluded for free by the tokenizer, which classifies it as punctuation rather than an operator symbol. A
 * check that grouped the two kinds would emit the wrong code for both.
 */
fun incompleteInput(text: String, language: ConditionLanguage): IncompleteInput? {
    val keywords = CONDITION_KEYWORDS.getValue(language)
    // Whitespace never becomes a token, and a comment is not input the grammar consumes — so the last
    // SIGNIFICANT token is what A12 is left holding, and `… And ;; note` ends on `And` just as `… And ` does.
    val last = tokenize(text).lastOrNull { it.kind != TokenKind.COMMENT } ?: return null
    val dangling = when (last.kind) {
        TokenKind.NAME -> keywords[last.text] == KeywordKind.OPERATOR
        TokenKind.OPERATOR_SYMBOL -> true
        TokenKind.PUNCTUATION -> last.text == ","
        else -> false
    }
    return if (dangling) IncompleteInput(last.text, last.start) else null
}

/**
 * The token the text ended on, and where.
 *
 * [spelling] is carried rather than re-derived because the author wrote it in their own condition language,
 * and echoing their spelling back is what makes the finding locatable in a long condition.
 */
data class IncompleteInput(val spelling: String, val at: Int)
