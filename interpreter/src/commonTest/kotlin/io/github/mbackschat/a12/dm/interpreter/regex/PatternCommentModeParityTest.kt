package io.github.mbackschat.a12.dm.interpreter.regex

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

/**
 * The places a Java pattern's syntax is read through a character class rather than a fixed character: what
 * COMMENTS mode skips, which digits extend a backreference, and which digits a hexadecimal escape takes. `java.util.regex.Pattern` reads both through its own
 * ASCII tables, so a platform's Unicode tables read them wrongly. The JVM actual IS `Pattern`, so each expectation is
 * Java's own result (also run on JDK 21.0.12 outside the suite, 2026-10-21), and the JS actual must reproduce it.
 */
class PatternCommentModeParityTest {

    private fun m(pattern: String, text: String) = compilePattern(pattern).matches(text)

    private fun char(code: Int) = Char(code).toString()

    @Test
    fun commentsModeSkipsAsciiWhitespaceAlone() {
        val observed = listOf(0x0020, 0x000B, 0x000C, 0x00A0, 0x2003, 0x0085).associateWith { code ->
            m("(?x)a${char(code)}b", "ab") to m("(?x)a${char(code)}b", "a${char(code)}b")
        }
        val skipped = true to false
        val literal = false to true
        assertEquals(
            mapOf(0x0020 to skipped, 0x000B to skipped, 0x000C to skipped, 0x00A0 to literal, 0x2003 to literal,
                0x0085 to literal),
            observed,
        )
    }

    /** A comment runs to a line feed, or to a carriage return unless UNIX_LINES is on; no other terminator ends it. */
    @Test
    fun aCommentEndsAtTheLineTerminatorsJavaReads() {
        val observed = listOf(0x000A, 0x000D, 0x0085, 0x2028).associateWith { code ->
            m("(?x)a#c${char(code)}b", "ab") to m("(?xd)a#c${char(code)}b", "ab")
        }
        assertEquals(
            mapOf(0x000A to (true to true), 0x000D to (true to false), 0x0085 to (false to false),
                0x2028 to (false to false)),
            observed,
        )
    }

    /** `\1` then a non-ASCII digit is backreference 1 and a literal, even where a thirteenth group exists. */
    @Test
    fun onlyAsciiDigitsExtendABackreference() {
        val groups = ('a'..'m').joinToString("") { "($it)" }
        val text = ('a'..'m').joinToString("")
        val arabicThree = char(0x0663)
        assertEquals(
            listOf(true, false, true),
            listOf(
                m("$groups\\1$arabicThree", "${text}a$arabicThree"),
                m("$groups\\1$arabicThree", "${text}m"),
                m("(a)\\$arabicThree", "a$arabicThree"),
            ),
        )
    }

    /** A class's `\x` and `\u` escapes take ASCII hex digits only; Java refuses the pattern otherwise. */
    @Test
    fun aHexEscapeTakesAsciiHexDigitsAlone() {
        assertEquals(true, m("[\\x33]", "3"))
        val three = char(0x0663)
        for (pattern in listOf("[\\x$three$three]", "[\\x${char(0xFF13)}${char(0xFF13)}]", "[\\u${char(0x0660)}${char(0x0660)}$three$three]")) {
            assertFails(pattern) { compilePattern(pattern) }
        }
    }
}
