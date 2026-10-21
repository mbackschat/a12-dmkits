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

    /**
     * A `'…'` that does not match the name terminal: unclosed, empty, or starting with a digit. Separate from
     * [UNTERMINATED_STRING] because the two are different terminals with different recovery — a string
     * literal genuinely extends to the end of the text, while a failed quoted name stops at the first
     * character the terminal cannot take, so the structure after it stays visible to every later analysis.
     */
    UNTERMINATED_QUOTED_NAME,

    /** A `;;` comment up to, not including, the next carriage return or line feed. */
    COMMENT,

    /** A symbolic operator such as `>` or `==`. */
    OPERATOR_SYMBOL,

    /** Structure: brackets, parentheses, separators, path steps. */
    PUNCTUATION,

    /**
     * A character no terminal of the lexer can begin — A12 answers `MVK_LEXER_STANDARD_ERROR`, naming the
     * character and its position. Its own kind rather than [PUNCTUATION], which is what it used to become:
     * read as structure, a stray `%` travelled into every later analysis as though A12 had accepted it.
     */
    ILLEGAL_CHARACTER,
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
 * reference. A **comment** opens with `;;` and runs to the next carriage return or line feed.
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
            char in LEXER_WHITESPACE -> index++

            char == ';' && text.startsWith(";;", index) -> {
                index = text.indexOfAny(COMMENT_TERMINATORS, index).takeIf { it >= 0 } ?: text.length
                tokens += Token(TokenKind.COMMENT, text.substring(start, index), start, index)
            }

            char == '"' -> {
                // The scanner reports whether it found a closer. Re-deriving that from the last CHARACTER —
                // which is what this did until 2026-09-12 — calls a literal complete whenever it happens to
                // end on a quote, so `"A""` and `"""` both read as terminated when the doubling rule says
                // neither is, and every consumer downstream is handed a literal that swallowed the rest.
                val closed = endOfStringLiteral(text, index)
                index = closed ?: text.length
                tokens += Token(
                    if (closed != null) TokenKind.STRING else TokenKind.UNTERMINATED_STRING,
                    text.substring(start, index),
                    start,
                    index,
                )
            }

            char == '\'' -> {
                // The terminal is `'` STR (STR|DIGIT)* `'`, so the closer is the one that follows an
                // unbroken run of name characters — NOT merely the next quote anywhere in the text. Taking
                // the next quote is what this did until 2026-09-13, and on `'Tier) And 'x'` it produced the
                // name `Tier) And `, which the reference extractor then reported as a field nobody wrote.
                var scan = index + 1
                while (scan < text.length && text[scan].isNamePart()) scan++
                val closed = scan < text.length && text[scan] == '\''
                val named = scan > index + 1 && text[index + 1].isNameStart()
                index = if (closed) scan + 1 else scan
                tokens += if (closed && named) {
                    // The Kernel's lexer strips the quotes too (`setTextUnquotedInLexer`) and keeps the
                    // escape in the token TYPE, which is what [TokenKind.QUOTED_NAME] reproduces.
                    Token(TokenKind.QUOTED_NAME, text.substring(start + 1, index - 1), start, index)
                } else {
                    // Empty, digit-leading, or unclosed — all three draw `MVK_LEXER_STANDARD_ERROR` from A12
                    // (`RuleConditionSyntaxLawsTest`), so they need no finer distinction here.
                    Token(TokenKind.UNTERMINATED_QUOTED_NAME, text.substring(start, index), start, index)
                }
            }

            char.isAsciiDigit() -> {
                while (index < text.length && text[index].isAsciiDigit()) index++
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
                // `#` begins exactly one terminal, the fixed literal `#Zeile`, so it is judged by what follows.
                val illegal = symbol.length == 1 && symbol[0].beginsNoTerminal() ||
                    symbol == "#" && !text.startsWith(ROW_ITERATION, start)
                tokens += Token(
                    if (illegal) TokenKind.ILLEGAL_CHARACTER else symbol.kindOfSymbol(),
                    symbol,
                    start,
                    index,
                )
            }
        }
    }
    return tokens
}

/**
 * Whether NO terminal of the lexer can begin with this character.
 *
 * Stated over all characters rather than only the ones the fallback reaches, so it can be read against the
 * grammar on its own. The earlier branches of [tokenize] already consume whitespace, digits, name characters
 * and both quotes, so those arms are unreachable from its one call site and are kept because dropping them
 * would make the predicate true of characters that plainly do begin a terminal.
 *
 * Derived from `Base_ConditionLexer.g4` rather than assembled: the grammar has no catch-all rule, so a
 * character outside its terminals is a token-recognition failure. [STANDALONE_PUNCTUATION] is the set the
 * terminals spell on their own, and three characters are deliberately excluded from the judgement instead of
 * added to it, each for its own reason:
 *
 * - `!` and `;` reach this point only when the multi-character terminal they belong to did NOT match — `!=`
 *   is tried before the fallback and `;;` is consumed as a comment earlier — so a lone one is illegal and is
 *   reported. Both measured ([KERNEL-FINDINGS KF309]).
 * - `#` begins a terminal, but only one: `ZEILE_ITER`, the fixed literal `#Zeile`, which the multi-language
 *   handler never retypes and which is the only `#` either grammar spells. So whether a `#` is legal depends
 *   on what follows it, and [tokenize] judges it there. It was once left unreported on the belief that
 *   `#Zeile` had other language spellings; the grammar has none, and a lone `#` measures illegal.
 */
private fun Char.beginsNoTerminal(): Boolean =
    this !in STANDALONE_PUNCTUATION && this != '#' && this !in LEXER_WHITESPACE && !isAsciiDigit() &&
        !isNameStart() && this != '"' && this != '\''

/**
 * The lexer's `WS` rule: space, carriage return, tab, form feed and line feed, and nothing else. Every other
 * character Unicode calls whitespace — a no-break space, a vertical tab, an em space — begins no terminal and
 * draws the lexer error ([KERNEL-FINDINGS KF309]), which `Char.isWhitespace` would have discarded instead.
 */
private const val LEXER_WHITESPACE = " \r\t\u000C\n"

/**
 * What ends a comment: `Comment : ';;' ~('\r' | '\n')* …`, so a lone carriage return does as well as a line
 * feed, and the text after it is live — measured, a character harmless inside the comment draws the lexer
 * error there (`rulekit.validate.laws.ConditionCharacterLawsTest`, [KERNEL-FINDINGS KF309]).
 */
private val COMMENT_TERMINATORS = charArrayOf('\r', '\n')

/**
 * Whether the lexer finds no token at all in this text — every character is one of its five whitespace
 * characters. Text of any other space reaches the lexer and fails there ([KERNEL-FINDINGS KF309]), which is a
 * different verdict from an empty token stream.
 */
internal fun String.isLexerBlank(): Boolean = all { it in LEXER_WHITESPACE }

private const val ROW_ITERATION = "#Zeile"

/** The punctuation A12's terminals spell as a complete token, `#` excepted — see [beginsNoTerminal]. */
private const val STANDALONE_PUNCTUATION = "+-*/^><=()[]{},.@$"

/**
 * Scan past a `"…"` literal, where `""` is an escaped quote rather than an empty literal then a new one.
 *
 * Returns the index just past the closing quote, or `null` when the text ran out first — the caller cannot
 * recover that from the returned index, because a literal that runs off the end frequently ends on a quote.
 */
private fun endOfStringLiteral(text: String, open: Int): Int? {
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
    return null
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

private fun Char.isNamePart(): Boolean = isNameStart() || isAsciiDigit()

private const val NAME_EXTRA = "_ÄÜÖäüöß:"

/** KF493: an Arabic digit is a lexical error; A12's digit terminal is ASCII. */
internal fun Char.isAsciiDigit(): Boolean = this in '0'..'9'

/**
 * A letter or digit a NAME token can carry: [isNamePart] without its `_` and `:`. The checker asks this where it
 * once asked a platform's `isLetterOrDigit`, whose Unicode tables differ between the JVM and Node and from A12's
 * lexer (KERNEL-FINDINGS KF587).
 */
internal fun Char.isNameLetterOrDigit(): Boolean = isNamePart() && this != '_' && this != ':'
