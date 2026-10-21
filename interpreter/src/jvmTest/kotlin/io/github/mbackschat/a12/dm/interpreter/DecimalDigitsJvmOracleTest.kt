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
        val forbidden = Regex("""\.(isDigit|isLetter|isLetterOrDigit|isWhitespace|digitToInt|digitToIntOrNull)\(\)|Char::(isDigit|isLetter|isLetterOrDigit|isWhitespace)\b|\\\\?s(?![A-Za-z])""")
        // A predefined type's pattern is Java regex syntax, which `JavaPatternToJs` translates for Node.
        val javaPatternSites = setOf("model/Model.kt")
        val root = File("src/commonMain/kotlin/io/github/mbackschat/a12/dm/interpreter")
        val sources = root.walk().filter { it.extension == "kt" }.toList()
        assertTrue(sources.size > 100, "scanned ${sources.size} files under ${root.absolutePath}")
        val hits = sources.filter { it.relativeTo(root).invariantSeparatorsPath !in javaPatternSites }.flatMap { file ->
            file.readLines().withIndex()
                .filter { (_, line) -> !line.trimStart().startsWith("*") && !line.trimStart().startsWith("//") }
                .filter { (_, line) -> forbidden.containsMatchIn(line.substringBefore("//")) }
                .map { (at, line) -> "${file.relativeTo(root)}:${at + 1}: ${line.trim()}" }
        }
        assertEquals(emptyList(), hits)
    }
}
