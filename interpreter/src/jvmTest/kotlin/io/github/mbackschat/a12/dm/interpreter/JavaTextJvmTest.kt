package io.github.mbackschat.a12.dm.interpreter

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Each reading in `JavaText.kt` against the JDK call it spells out. JVM-only because the JDK is the oracle; the
 * readings themselves are shared code, so Node answers as these rows do.
 */
class JavaTextJvmTest {

    @Test
    fun whitespaceIsJavasOverEveryChar() {
        val wrong = (0..0xFFFF).map(Int::toChar).filter { it.isJavaWhitespace() != Character.isWhitespace(it) }
        assertEquals(emptyList(), wrong.map { "U+%04X".format(it.code) })
    }

    /** Our own classification is the JVM's `isBlank` per character, so Node classifies as the JVM does. */
    @Test
    fun unicodeBlankIsJavaWhitespaceOrASpaceCharOverEveryChar() {
        val wrong = (0..0xFFFF).map(Int::toChar)
            .filter { "$it".isUnicodeBlank() != (Character.isWhitespace(it) || Character.isSpaceChar(it)) }
        assertEquals(emptyList(), wrong.map { "U+%04X".format(it.code) })
    }

    @Test
    fun trimAndBlankAreJavas() {
        val texts = listOf("", " ", "\u00A0", " a ", "\u00A0a\u00A0", "\t\u2007\n", "\u3000", "\u001F x \u0000", "\u202F")
        assertEquals(texts.map { (it as java.lang.String).trim() }, texts.map { it.javaTrimmed() })
        assertEquals(texts.map { (it as java.lang.String).isBlank }, texts.map { it.isJavaBlank() })
    }

    @Test
    fun javaIntIsIntegerParseInt() {
        val texts = listOf("", "+", "-", "0", "-0", "+7", "42", "007", " 1", "1 ", "\u0663", "-\u0661\u0662", "\uFF15",
            "\u0E52\u0E50", "2147483647", "2147483648", "-2147483648", "-2147483649", "99999999999", "1.0", "1e2", "--1")
        val java = texts.map { runCatching { Integer.parseInt(it) }.getOrNull() }
        assertEquals(java, texts.map { it.javaIntOrNull() })
    }

    @Test
    fun asciiIntRefusesEveryOtherDigit() {
        val texts = listOf("", "+", "-", "0", "-0", "+7", "42", "007", "\u0663", "\uFF15", "2147483647", "2147483648",
            "-2147483648", "9223372036854775807", "9223372036854775808", "-9223372036854775808", "-9223372036854775809")
        val asciiOnly = texts.map { text -> runCatching { java.lang.Long.parseLong(text) }.getOrNull()
            ?.takeIf { text.all { c -> c in "+-0123456789" } } }
        assertEquals(asciiOnly, texts.map { it.asciiLongOrNull() })
        assertEquals(asciiOnly.map { it?.takeIf { v -> v in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt() }, texts.map { it.asciiIntOrNull() })
    }

    @Test
    fun caseFoldAgainstAnAsciiLiteralIsEqualsIgnoreCase() {
        val literals = ('a'..'z') + ('A'..'Z') + ('0'..'9')
        val wrong = (0..0xFFFF).map(Int::toChar).flatMap { c -> literals.map { l -> c to l } }
            .filter { (c, l) -> "$c".equalsIgnoringCaseAsJavaDoes("$l") != (("$c" as java.lang.String).equalsIgnoreCase("$l")) }
        assertEquals(emptyList(), wrong.take(20).map { (c, l) -> "U+%04X~%s".format(c.code, l) })
        assertEquals(listOf(true, true, false), listOf("fal\u017Fe", "TRUE", "fals").map { it.equalsIgnoringCaseAsJavaDoes(if (it == "TRUE") "true" else "false") })
    }
}
