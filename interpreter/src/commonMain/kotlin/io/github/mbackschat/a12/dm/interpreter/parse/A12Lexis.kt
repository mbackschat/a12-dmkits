package io.github.mbackschat.a12.dm.interpreter.parse

/**
 * The character classes of A12's condition and message-parameter lexers, read the same on every platform.
 *
 * Both kernel grammars spell their sets out: a name is drawn from the ASCII letters, `_` and the seven German
 * letters below, a digit is ASCII only, and whitespace is five ASCII characters. Kotlin's `Char.isLetterOrDigit`,
 * `Char.isWhitespace` and a regex `\s` answer from Unicode instead, the JVM from `Character` and Kotlin/JS from its
 * own tables, so an Arabic-Indic digit, an accented letter or a no-break space classified differently per platform
 * and differently from A12, which refuses each of them (IF415).
 *
 * The kernel's name set also admits `:`, which no element name can carry (`MVK_NAME_INVALID_DOT_COLON_A12_DV`), so
 * it stays outside [isNamePart] as before.
 */
internal object A12Lexis {
    private const val GERMAN_LETTERS = "ÄÖÜäöüß"

    /** The kernel lexers' whitespace, as a regex character class for patterns built from strings. */
    const val SPACE_CLASS: String = "[ \\t\\n\\f\\r]"

    fun isDigit(c: Char): Boolean = c in '0'..'9'

    fun isNameStart(c: Char): Boolean =
        c in 'a'..'z' || c in 'A'..'Z' || c == '_' || (c >= '\u0080' && GERMAN_LETTERS.indexOf(c) >= 0)

    fun isNamePart(c: Char): Boolean = isNameStart(c) || isDigit(c)

    fun isSpace(c: Char): Boolean = c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\u000C'
}
