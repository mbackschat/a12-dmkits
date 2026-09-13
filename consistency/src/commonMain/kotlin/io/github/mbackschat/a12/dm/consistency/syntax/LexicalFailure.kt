package io.github.mbackschat.a12.dm.consistency.syntax

/**
 * Whether condition text can be TOKENIZED at all — the phase before any grammar question.
 *
 * A12 answers a condition it cannot lex with `MVK_LEXER_STANDARD_ERROR`, which is a different code from the
 * three the parser phases raise ([KERNEL-FINDINGS KF300]). The distinction is worth keeping because it tells
 * an author which half of the text is wrong: a lexical failure is decided by the characters alone, so it is
 * reportable without a grammar and without any risk of rejecting something A12 accepts.
 *
 * **Only measured shapes are reported, and there are three.** An unterminated string literal; a `'…'` name
 * that does not match its terminal (`'` STR (STR|DIGIT)* `'`) — unclosed, empty, or starting with a digit,
 * all three measured as `MVK_LEXER_STANDARD_ERROR`, so they need no finer distinction; and a CHARACTER no
 * terminal can begin, which is the class A12's own message names ("Character '%s' at position %d is
 * incorrect").
 *
 * **The character class is DERIVED from the grammar, which is what makes reporting it safe.**
 * `Base_ConditionLexer.g4` has no catch-all rule, so anything outside its terminals is a token-recognition
 * failure; the admitted set is the `STR` fragment, `DIGIT`, whitespace, the quotes, and the punctuation the
 * operator and bracket terminals spell. Measured against that reading and agreeing with it on every row
 * ([KERNEL-FINDINGS KF309]): `%`, `&`, `|`, `;`, `?`, `~`, a backslash, `é` and `€` all draw
 * `MVK_LEXER_STANDARD_ERROR`, while `$`, `@`, `,` and `ß` — characters the terminals DO spell — lex and are
 * refused a phase later with `MVK_UNEXPECTED_TOKEN`. Those four are the control: without them the grid would
 * be measuring the slot rather than the character.
 *
 * **A character is judged only OUTSIDE a literal**, since `StringLiteral` admits everything but a quote — so
 * a checker scanning the raw text would refuse conditions A12 accepts, and [tokenize] is what keeps the
 * question in the right place. A comment is the same case and is consumed before the fallback is reached.
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
 * is deliberately NOT the treatment the completeness family got — there a measured boundary existed. The same
 * caveat covers a failed quoted name, which cannot swallow a delimiter but is measured to lose the same way
 * when a parse error sits earlier in the text (`FieldFilled(Tier)) And 'x` is `MVK_UNEXPECTED_TOKEN`). What
 * it no longer does is swallow the text itself, since the scan stops at the first character the terminal
 * cannot take.
 *
 * **No condition language is taken, and that is a claim rather than an omission.** The lexer's KEYWORD
 * resolution is per language, but tokenization into names, literals and punctuation is not: a quote opens and
 * closes the same way in both. Threading a language here would suggest a dependency the tokenizer does not
 * have.
 */
fun lexicalFailure(text: String): LexicalFailure? = tokenize(text)
    .firstNotNullOfOrNull { token -> token.lexicalKind()?.let { LexicalFailure(token.start, it, token.text) } }

private fun Token.lexicalKind(): LexicalFailureKind? = when (kind) {
    TokenKind.UNTERMINATED_STRING, TokenKind.UNTERMINATED_QUOTED_NAME -> LexicalFailureKind.UNPAIRED_QUOTE
    TokenKind.ILLEGAL_CHARACTER -> LexicalFailureKind.ILLEGAL_CHARACTER
    else -> null
}

/**
 * Why the lexer stopped — the two classes draw ONE code from A12 and need two different sentences.
 *
 * They are kept apart only for the message. Telling an author their quote is unclosed when what they wrote
 * was a stray `%` sends them to the wrong end of the text, and a confidently wrong explanation costs more
 * than a vague one.
 */
enum class LexicalFailureKind {
    /** A `"` or `'` the lexer cannot pair. */
    UNPAIRED_QUOTE,

    /** A character no terminal of the lexer can begin. */
    ILLEGAL_CHARACTER,
}

/**
 * Where the lexer stopped, and on what.
 *
 * The offset is carried because it is the only part an author cannot re-derive by eye in a long condition,
 * and because the Kernel's own message for this failure names a position. [text] is the offending slice, so
 * a message can quote the character rather than only locate it.
 */
data class LexicalFailure(val at: Int, val kind: LexicalFailureKind, val text: String)
