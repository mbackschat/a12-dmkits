package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals

/** The checker uses the shared translator's represented domain and the host's whole-value Java meaning. */
class KeyPatternTest {
    private fun matches(pattern: String, value: String): Boolean? = KeyPattern.parse(pattern)?.matches(value)

    @Test
    fun representedPatternsMatchWholeValues() {
        val rows = listOf(
            Triple(".{2}", "AB", true), Triple(".{2}", "A", false),
            Triple("\\d{2}", "12", true), Triple("\\d{2}", "1A", false),
            Triple("(AB|CD)", "CD", true), Triple("(AB|CD)", "CE", false),
            Triple("(?i:ab)", "AB", true), Triple("(?i:ab)", "AC", false),
            Triple("\\h{2}", "  ", true), Triple("\\h{2}", "A ", false),
            Triple(".", "\u0085", false), Triple(".", "\uD83D\uDE00", true),
            Triple("\\s", "\u00A0", false), Triple("\\R", "\r\n", true),
            Triple("\\S", "\u00A0", true),
            Triple("(a|(b))\\2c", "ac", false), Triple("(a|(b))\\2c", "bbc", true),
            Triple("a_b c", "a_b c", true), Triple("[a-c ]+", "abd", false),
            Triple("[^0-9]", Char(0xD83D).toString(), true),
            Triple("[^0-9]{2}", Char(0xD83D) + "a", true),
            Triple(".{2}", "AB\n", false),
        )
        rows.forEach { (pattern, value, expected) -> assertEquals(expected, matches(pattern, value), "$pattern / $value") }
    }

    @Test
    fun unsupportedOrKernelRejectedPatternsStayUnjudged() {
        assertEquals(null, matches("\\N{LATIN SMALL LETTER A}", "a"))
        assertEquals(null, matches("[\\S]", "\u00A0"))
        assertEquals(null, matches("[\\x{A0}]", "\u00A0"))
        assertEquals(null, matches("[\\p{Lower}]", "a"))
        assertEquals(null, matches("(?iu:é)", "É"))
        assertEquals(null, matches("a$\n", "a\n"))
        assertEquals(null, matches("a$", "a\n"))
        assertEquals(null, matches("\\X", "a"))
        assertEquals(null, matches("\\b{g}", "a"))
        assertEquals(null, matches("a++", "aaa"))
        assertEquals(null, matches("(?<=A)B", "B"))
        assertEquals(null, matches("[", "A"))
    }
}
