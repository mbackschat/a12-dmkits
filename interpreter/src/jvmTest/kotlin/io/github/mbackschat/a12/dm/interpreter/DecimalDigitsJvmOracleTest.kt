package io.github.mbackschat.a12.dm.interpreter

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * IF415 — the shared digit table against its oracle, and the guard that keeps platform tables out of `commonMain`.
 *
 * JVM-only on purpose: `Character` is the oracle, and A12 converts a quoted Number with `java.math.BigDecimal`,
 * which reads digits through it (KF564). The table answers the same in Node by construction, so this pins the one
 * place it could be wrong.
 */
class DecimalDigitsJvmOracleTest {

    @Test
    fun theTableIsTheJvmsDecimalDigitSetOverTheWholeBmp() {
        val wrong = (0..0xFFFF).map(Int::toChar).filter { DecimalDigits.valueOf(it) != Character.digit(it, 10) }
        assertEquals(emptyList(), wrong.map { "U+%04X".format(it.code) })
    }

    /**
     * Kotlin's `Char` classifiers and a Kotlin `Regex` `\s` answer from the JVM's `Character` but from Kotlin/JS's
     * own tables in Node, so each one in shared code is a place the two platforms can read a character
     * differently. `DecimalDigits` and `parse.A12Lexis` carry the sets A12 uses instead.
     */
    @Test
    fun sharedCodeClassifiesNoCharacterThroughAPlatformTable() {
        val root = File("src/commonMain/kotlin/io/github/mbackschat/a12/dm/interpreter")
        val sources = root.walk().filter { it.extension == "kt" }.toList()
        assertTrue(sources.size > 100, "scanned ${sources.size} files under ${root.absolutePath}")
        val hits = sources.flatMap { file ->
            val path = file.relativeTo(root).invariantSeparatorsPath
            // A predefined type's pattern is Java regex syntax, which `JavaPatternToJs` translates for Node.
            val forbidden = if (path in JAVA_PATTERN_SITES) PLATFORM_TABLE else PLATFORM_TABLE_OR_REGEX_SPACE
            file.readLines().withIndex()
                .filter { (_, line) -> !line.trimStart().startsWith("*") && !line.trimStart().startsWith("//") }
                .filter { (_, line) -> forbidden.containsMatchIn(line.substringBefore("//")) }
                .map { (at, line) -> "$path:${at + 1}: ${line.trim()}" }
        }
        assertEquals(emptyList(), hits)
    }

    /** The scan catches each spelling it names, so an empty scan is evidence rather than a mismatch. */
    @Test
    fun theScanCatchesEveryClassifierSpelling() {
        val planted = listOf("c.isDigit()", "s.isBlank()", "s?.isNullOrBlank()", "s.isNotBlank()", "s.ifBlank { x }",
            "s.trim()", "s.trimEnd()", "map(String::trim)", "s.toIntOrNull()", "s.toLongOrNull()", "s.toDoubleOrNull()",
            "a.equals(b, ignoreCase = true)")
        assertEquals(emptyList(), planted.filterNot { PLATFORM_TABLE.containsMatchIn(it) })
        assertEquals(emptyList(), listOf("s.trim { it <= ' ' }", "n.toInt()", "s.isJavaBlank()").filter { PLATFORM_TABLE.containsMatchIn(it) })
    }

    private companion object {
        val JAVA_PATTERN_SITES = setOf("model/Model.kt")

        /**
         * A platform classifier, a blank test or argument-free trim (Kotlin's whitespace includes the no-break spaces
         * Java's excludes), a string-to-number parse (Kotlin reads digits through its own table), or a case-folding
         * comparison: each answers from the JVM's tables on one platform and Kotlin/JS's on the other.
         */
        val PLATFORM_TABLE = Regex(
            """\.(isDigit|isLetter|isLetterOrDigit|isWhitespace|digitToInt|digitToIntOrNull)\(\)|Char::(isDigit|isLetter|isLetterOrDigit|isWhitespace)\b""" +
                """|\.(isBlank|isNotBlank|isNullOrBlank)\(|\.ifBlank\b|\.(trim|trimStart|trimEnd)\(\)|String::(isBlank|isNotBlank|trim|trimStart|trimEnd)\b""" +
                """|\.(toIntOrNull|toLongOrNull|toDoubleOrNull|toFloatOrNull|toShortOrNull|toByteOrNull)\(|ignoreCase\s*=\s*true""",
        )

        /** A Kotlin `Regex` `\s` as well, which reads the platform's whitespace table. */
        val PLATFORM_TABLE_OR_REGEX_SPACE = Regex(PLATFORM_TABLE.pattern + """|\\\\?s(?![A-Za-z])""")
    }
}
