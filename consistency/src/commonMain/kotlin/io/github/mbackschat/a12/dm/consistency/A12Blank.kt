package io.github.mbackschat.a12.dm.consistency

/**
 * Whether A12's member-level presence test reads this text as blank: every character is one Java's
 * `Character.isWhitespace` accepts.
 *
 * That is the test behind a rule's `MVK_ERROR_CODE_MISSING` and `MVK_CONDITION_MISSING` and a custom type's
 * `CUSTOM_FIELD_TYPE_CODENAME_MISSING` — measured on six spaces that split the definitions apart
 * ([KERNEL-FINDINGS KF310]): a vertical tab, an em space and an ideographic space are blank there, and the
 * no-break, figure and narrow no-break spaces are not. Kotlin's `isBlank` counts all six, so a checker asking it
 * named a missing member where A12 judged a present one. It is not the LEXER's notion, which is narrower still
 * and belongs to text that reaches the parser — see `isLexerBlank`.
 */
internal fun String.isA12Blank(): Boolean = all { it.isJavaWhitespace() }

/**
 * Java's `Character.isWhitespace`, spelled out so every target answers it alike: the space, line and paragraph
 * separators except the three no-break spaces, and nine controls. `A12BlankJvmTest` compares it with the JDK's
 * own answer for every `char`.
 */
internal fun Char.isJavaWhitespace(): Boolean = when (this) {
    '\t', '\n', '\u000B', '\u000C', '\r', '\u001C', '\u001D', '\u001E', '\u001F' -> true
    ' ', '\u1680', '\u2028', '\u2029', '\u205F', '\u3000' -> true
    in '\u2000'..'\u2006', in '\u2008'..'\u200A' -> true
    else -> false
}

/**
 * Java's `String.trim`: strips every character up to U+0020 from both ends, and nothing else — the no-break
 * space and the other Unicode spaces survive. `A12BlankJvmTest` compares it with the JDK's own answer.
 */
internal fun String.javaTrimmed(): String = trim { it <= ' ' }
