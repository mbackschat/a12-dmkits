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

    /**
     * A comment runs to `Pattern`'s line separator: a line feed under UNIX_LINES (`d`), otherwise also a carriage
     * return, U+0085, U+2028 and U+2029. The separator ends the comment without belonging to it, so an ASCII one is
     * then skipped as whitespace and a non-ASCII one is a literal; the text `a·b` is what tells those apart.
     */
    @Test
    fun aCommentEndsAtTheLineSeparatorsJavaReads() {
        val observed = listOf(0x000A, 0x000D, 0x0085, 0x2028, 0x2029).associateWith { code ->
            listOf("(?x)", "(?xd)").map { flags ->
                m("${flags}a#c${char(code)}b", "ab") to m("${flags}a#c${char(code)}b", "a${char(code)}b")
            }
        }
        val skipped = true to false
        val literal = false to true
        val eaten = false to false
        assertEquals(
            mapOf(
                0x000A to listOf(skipped, skipped),
                0x000D to listOf(skipped, eaten),
                0x0085 to listOf(literal, eaten),
                0x2028 to listOf(literal, eaten),
                0x2029 to listOf(literal, eaten),
            ),
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

    /** A `\x`, `\x{…}` or `\u` escape takes ASCII hex digits only, in a class or not, case-insensitive or not; Java refuses the pattern otherwise. */
    @Test
    fun aHexEscapeTakesAsciiHexDigitsAlone() {
        assertEquals(true, m("[\\x33]", "3"))
        assertEquals(true, m("(?i)\\x41", "a"))
        val three = char(0x0663)
        val four = char(0x0664)
        val one = char(0x0661)
        val zero = char(0x0660)
        val classes = listOf("[\\x$three$three]", "[\\x${char(0xFF13)}${char(0xFF13)}]", "[\\u$zero$zero$three$three]")
        val scalars = listOf("\\x$four$one", "\\x{$four$one}", "\\u$zero$zero$four$one").flatMap { listOf(it, "(?i)$it") }
        for (pattern in classes + scalars) {
            assertFails(pattern) { compilePattern(pattern) }
        }
    }

    /** A backslash before a non-ASCII character is that character inside a class too, alone or as a range end. */
    @Test
    fun anEscapedNonAsciiClassMemberIsALiteral() {
        val three = char(0x0663)
        assertEquals(
            listOf(true, false, true, false),
            listOf(
                m("[\\$three]", three),
                m("[\\$three]", "3"),
                m("[\\${char(0x0660)}-\\${char(0x0669)}]", char(0x0665)),
                m("[\\${char(0x0660)}-\\${char(0x0669)}]", "5"),
            ),
        )
    }
}
