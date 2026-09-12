package io.github.mbackschat.a12.dm.consistency.syntax

/** What a token is, at the granularity the checker's analyses need. */
enum class TokenKind {
    /** An identifier: an operator keyword or an entity name. */
    NAME,

    /**
     * A `'…'` name, quotes stripped. Separate from [NAME] because the quoting is what makes a name that
     * spells a keyword an entity reference, so a consumer that could not tell them apart would drop it.
     */
    QUOTED_NAME,

    /** A digit sequence. */
    NUMBER,

    /** A complete `"…"` literal, quotes included and doubled quotes intact. */
    STRING,

    /** A `"` with no closing partner — reported rather than swallowing the rest of the text. */
    UNTERMINATED_STRING,

    /** A `;;` comment through the end of its line. */
    COMMENT,

    /** A symbolic operator such as `>` or `==`. */
    OPERATOR_SYMBOL,

    /** Structure: brackets, parentheses, separators, path steps. */
    PUNCTUATION,
}

/**
 * @param text the token's value, which is its source slice except where the lexer strips syntax from it — a
 *     [TokenKind.QUOTED_NAME] carries the bare name, so [text] is shorter than the span.
 * @param start the offset the token begins at, and [end] the offset just past it, so a finding can carry a
 *     source span and a consumer can tell two adjacent tokens from two separated by whitespace.
 */
data class Token(val kind: TokenKind, val text: String, val start: Int, val end: Int)

/**
 * Tokenize A12 condition or operation text, following `Base_ConditionLexer.g4`.
 *
 * Two terminals decide whether everything downstream is trustworthy, because each can contain text shaped
 * exactly like structure. A **string literal** escapes an embedded quote by DOUBLING it and gives backslash
 * no special meaning at all — the C-style reading would end a literal early and read its tail as a
 * reference. A **comment** opens with `;;` and runs to the end of its line.
 *
 * A single-quoted name is A12's escape for a field whose name collides with a keyword; the token carries the
 * bare name under [TokenKind.QUOTED_NAME], which is the escape the Kernel keeps in its own token type.
 *
 * One deliberate difference: `Comment` in the grammar continues across newlines while the next non-blank line
 * also opens with `;;`, so the Kernel emits ONE token where this emits one per line. Nothing downstream reads
 * a comment's text, and every consumer skips the kind, so the split is invisible where it is consumed.
 *
 * This is a lexer, not a parser: it says what the pieces ARE, never whether they compose into a legal
 * condition. Nothing here rejects input — an unterminated literal is a token kind, so a caller decides.
 */
fun tokenize(text: String): List<Token> {
    val tokens = mutableListOf<Token>()
    var index = 0
    while (index < text.length) {
        val start = index
        val char = text[index]
        when {
            char.isWhitespace() -> index++

            char == ';' && text.startsWith(";;", index) -> {
                index = text.indexOf('\n', index).takeIf { it >= 0 } ?: text.length
                tokens += Token(TokenKind.COMMENT, text.substring(start, index), start, index)
            }

            char == '"' -> {
                index = endOfStringLiteral(text, index)
                val complete = index <= text.length && text.getOrNull(index - 1) == '"' && index - start >= 2
                tokens += Token(
                    if (complete) TokenKind.STRING else TokenKind.UNTERMINATED_STRING,
                    text.substring(start, index),
                    start,
                    index,
                )
            }

            char == '\'' -> {
                val close = text.indexOf('\'', index + 1)
                index = if (close < 0) text.length else close + 1
                // The Kernel's lexer strips the quotes too (`setTextUnquotedInLexer`) and keeps the escape in
                // the token TYPE, which is what [TokenKind.QUOTED_NAME] reproduces.
                tokens += Token(TokenKind.QUOTED_NAME, text.substring(start, index).trim('\''), start, index)
            }

            char.isDigit() -> {
                while (index < text.length && text[index].isDigit()) index++
                // A digit run followed by a letter is a NAME, not a number then a name: the lexer's
                // `NameOhnePunktZifferAmAnfang` is `ZiffernFolge STR (STR|DIGIT)*`, so `1City` is one
                // identifier. Splitting it would hand a consumer the reference `City`, which usually exists.
                if (index < text.length && text[index].isNameStart()) {
                    while (index < text.length && text[index].isNamePart()) index++
                    tokens += Token(TokenKind.NAME, text.substring(start, index), start, index)
                } else {
                    tokens += Token(TokenKind.NUMBER, text.substring(start, index), start, index)
                }
            }

            char.isNameStart() -> {
                while (index < text.length && text[index].isNamePart()) index++
                tokens += Token(TokenKind.NAME, text.substring(start, index), start, index)
            }

            else -> {
                val symbol = SYMBOLS.firstOrNull { text.startsWith(it, index) } ?: char.toString()
                index += symbol.length
                tokens += Token(symbol.kindOfSymbol(), symbol, start, index)
            }
        }
    }
    return tokens
}

/** Scan past a `"…"` literal, where `""` is an escaped quote rather than an empty literal then a new one. */
private fun endOfStringLiteral(text: String, open: Int): Int {
    var index = open + 1
    while (index < text.length) {
        if (text[index] != '"') {
            index++
        } else if (text.startsWith("\"\"", index)) {
            index += 2
        } else {
            return index + 1
        }
    }
    return text.length
}

/**
 * Multi-character symbols first, so `==` is never read as two `=` and `->` never as `-` then `>`. Order is
 * load-bearing: the list is scanned in sequence and a one-character prefix would match first.
 */
private val SYMBOLS = listOf("==", "!=", "<=", ">=", "->", "..")

private val OPERATOR_SYMBOLS = setOf("==", "!=", "<=", ">=", "<", ">", "+", "-", "*", "/", "^")

private fun String.kindOfSymbol(): TokenKind =
    // `/` and `-` are ambiguous by shape alone — a path separator and a path step share them with division
    // and subtraction — so they are classified here as operators only where no path reading exists, and the
    // consumer decides by position. `..` and `->` are always structure.
    if (this in OPERATOR_SYMBOLS && this != "/" && this != "-") TokenKind.OPERATOR_SYMBOL
    else TokenKind.PUNCTUATION

/** `STR` in the lexer: ASCII letters, underscore, the German umlauts and eszett, and colon. */
private fun Char.isNameStart(): Boolean = this in 'a'..'z' || this in 'A'..'Z' || this in NAME_EXTRA

private fun Char.isNamePart(): Boolean = isNameStart() || isDigit()

private const val NAME_EXTRA = "_ÄÜÖäüöß:"
