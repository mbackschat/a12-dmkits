package io.github.mbackschat.a12.dm.interpreter

/*
 * The text readings A12's JVM runtime applies, spelled out so every platform this engine runs on answers alike.
 *
 * Kotlin's blank tests and argument-free trims count the three no-break spaces as whitespace, which Java's
 * `Character.isWhitespace` and `String.trim` do not, and its string-to-number parses read digits through each
 * platform's own table. A site that reads text A12 also reads names which reading it takes with one of these, and
 * `DecimalDigitsJvmOracleTest` refuses the platform spellings in shared code. `JavaTextJvmTest` holds each to the JDK.
 */

/** Java's `Character.isWhitespace`: the space, line and paragraph separators except the three no-break spaces, and
 *  nine controls. */
internal fun Char.isJavaWhitespace(): Boolean = when (this) {
    '\t', '\n', '\u000B', '\u000C', '\r', '\u001C', '\u001D', '\u001E', '\u001F' -> true
    ' ', '\u1680', '\u2028', '\u2029', '\u205F', '\u3000' -> true
    in '\u2000'..'\u2006', in '\u2008'..'\u200A' -> true
    else -> false
}

/** Java's `String.isBlank`: every character is [isJavaWhitespace], so a no-break space is text. */
internal fun String.isJavaBlank(): Boolean = all { it.isJavaWhitespace() }

/**
 * Whether every character is a Unicode space: Java's whitespace together with the three no-break spaces it excludes,
 * which is what Kotlin's `isBlank` answers on the JVM, spelled out so Node answers it alike. For a classification of
 * our own, never for a reading of A12's.
 */
internal fun String.isUnicodeBlank(): Boolean = all { it.isJavaWhitespace() || it in NO_BREAK_SPACES }

private const val NO_BREAK_SPACES = "\u00A0\u2007\u202F"

/** Only ASCII letters change case, for our own identifiers (an enum's name) where Kotlin's `lowercase` reads tables. */
internal fun String.asciiLowercase(): String = buildString(length) {
    this@asciiLowercase.forEach { append(if (it in 'A'..'Z') it + ('a' - 'A') else it) }
}

/** Java's `String.trim`: every character up to U+0020 from both ends, and nothing else. */
internal fun String.javaTrimmed(): String = trim { it <= ' ' }

/**
 * Java's `Integer.parseInt`: an optional `+` or `-`, then at least one digit as `Character.digit` reads it
 * ([DecimalDigits]), within `Int`'s range; null wherever Java throws.
 */
internal fun String.javaIntOrNull(): Int? {
    val negative = startsWith('-')
    val start = if (negative || startsWith('+')) 1 else 0
    if (start == length) return null
    var value = 0L
    for (i in start until length) {
        val digit = DecimalDigits.valueOf(this[i])
        if (digit < 0) return null
        value = value * 10 + digit
        if (value > Int.MAX_VALUE.toLong() + 1) return null
    }
    val signed = if (negative) -value else value
    return if (signed in Int.MIN_VALUE..Int.MAX_VALUE) signed.toInt() else null
}

/**
 * An optional `+` or `-`, then ASCII digits only, within `Long`'s range; null otherwise. For text a lexer, a regex or
 * a number formatter has already restricted to `0-9`, where any other digit cannot arrive and must not be read.
 */
internal fun String.asciiLongOrNull(): Long? {
    val negative = startsWith('-')
    val start = if (negative || startsWith('+')) 1 else 0
    if (start == length) return null
    var value = 0L
    for (i in start until length) {
        val c = this[i]
        if (c !in '0'..'9') return null
        val digit = c - '0'
        // Accumulate negatively, so Long.MIN_VALUE's magnitude fits.
        if (value < (Long.MIN_VALUE + digit) / 10) return null
        value = value * 10 - digit
    }
    return if (negative) value else if (value == Long.MIN_VALUE) null else -value
}

/** [asciiLongOrNull] within `Int`'s range. */
internal fun String.asciiIntOrNull(): Int? = asciiLongOrNull()?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()

/**
 * Java's `String.equalsIgnoreCase` against an ASCII [literal]. Java folds four non-ASCII letters onto ASCII ones —
 * U+017F (long s) onto `s`, U+212A (Kelvin sign) onto `k`, and U+0130 and U+0131 onto `i` — so A12 reads a Boolean cell
 * `fal\u017Fe` as false;
 * Kotlin's own fold reads each platform's case table instead. `JavaTextJvmTest` compares every `char` with the JDK.
 */
internal fun String.equalsIgnoringCaseAsJavaDoes(literal: String): Boolean =
    length == literal.length && indices.all { javaCaseFold(this[it]) == javaCaseFold(literal[it]) }

private fun javaCaseFold(c: Char): Char = when (c) {
    in 'A'..'Z' -> c + ('a' - 'A')
    '\u017F' -> 's'
    '\u212A' -> 'k'
    '\u0130', '\u0131' -> 'i'
    else -> c
}
