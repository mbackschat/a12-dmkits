package io.github.mbackschat.a12.dm.consistency.syntax

/**
 * Whether condition text can be TOKENIZED at all — the phase before any grammar question.
 *
 * A12 answers a condition it cannot lex with `MVK_LEXER_STANDARD_ERROR`, which is a different code from the
 * three the parser phases raise ([KERNEL-FINDINGS KF300]). The distinction is worth keeping because it tells
 * an author which half of the text is wrong: a lexical failure is decided by the characters alone, so it is
 * reportable without a grammar and without any risk of rejecting something A12 accepts.
 *
 * **Only the measured shape is reported.** An unterminated string literal is the one the laws tier carries.
 * A12's message text ("Character '%s' at position %d is incorrect") shows it also refuses illegal CHARACTERS,
 * but which characters those are is unmeasured — `@@@` lexes cleanly and fails a phase later — so nothing is
 * claimed about them here and the report says so.
 *
 * **And an unterminated literal is not ONE class — one measured shape draws a different code, and this family
 * still reports it.** `MVK_LEXER_STANDARD_ERROR` is what A12 says when the lexer's failure is the first thing
 * it meets; where the unterminated literal runs on and swallows a delimiter the grammar was waiting for,
 * the parser fails earlier and `MVK_UNEXPECTED_TOKEN` wins instead (`FieldFilled("x) And [Tier] == "abc"` —
 * the first literal eats the call's closing paren). Three other shapes, including one inside an unclosed call
 * and one after an earlier complete literal, are all `MVK_LEXER_STANDARD_ERROR`, so the two cannot be told
 * apart by counting quotes, by delimiter balance, or by whether an earlier literal exists — every such rule
 * was checked against the four rows and each puts a differently-answered shape on the wrong side.
 *
 * Which error wins is a question about the PARSER's position, so no grammar-free rule reaches it and none is
 * invented here: the family keeps reporting, the report carries a gap naming the shape, and the grammatical
 * increment is what fixes it. The cost is one wrong code on that shape rather than a missed defect, and this
 * is deliberately NOT the treatment the completeness family got — there a measured boundary existed.
 *
 * **No condition language is taken, and that is a claim rather than an omission.** The lexer's KEYWORD
 * resolution is per language, but tokenization into names, literals and punctuation is not: a quote opens and
 * closes the same way in both. Threading a language here would suggest a dependency the tokenizer does not
 * have.
 */
fun lexicalFailure(text: String): LexicalFailure? =
    tokenize(text)
        .firstOrNull { it.kind == TokenKind.UNTERMINATED_STRING }
        ?.let { LexicalFailure(it.start) }

/**
 * A quote with no closing partner, at [at].
 *
 * The offset is carried because it is the only part an author cannot re-derive by eye in a long condition,
 * and because the Kernel's own message for this failure names a position.
 */
data class LexicalFailure(val at: Int)
