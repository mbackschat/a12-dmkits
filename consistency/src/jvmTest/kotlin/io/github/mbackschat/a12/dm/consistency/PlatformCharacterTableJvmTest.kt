package io.github.mbackschat.a12.dm.consistency

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * No shared source of the checker classifies a character through a platform table (KERNEL-FINDINGS KF587).
 *
 * Kotlin's `Char` classifiers answer from the JVM's `Character` and from Kotlin/JS's own tables in Node, so each
 * one in common code is a place the two checkers can read a character differently, and both differently from A12,
 * whose lexers and patterns spell their sets out. The tokenizer's lexer classes and `isJavaWhitespace` carry those
 * sets instead. The shared regex translator is scanned too, because both checkers compile it; its emitted `\s`
 * is JavaScript pattern text rather than a classification, so only the classifier calls are refused there.
 *
 * Kotlin's blank tests and its argument-free trims ask the same tables, and they are the sharper case, because Java
 * reads the no-break spaces as text where Kotlin strips them: a no-break-space message text or enum label, which A12
 * admits, was reported missing that way (KERNEL-FINDINGS KF310). `isA12Blank`, `isLexerBlank`, `isUnicodeBlank` and
 * the `javaTrimmed` family name which reading each site takes.
 */
class PlatformCharacterTableJvmTest {

    @Test
    fun sharedCodeClassifiesNoCharacterThroughAPlatformTable() {
        val roots = listOf(File("src/commonMain/kotlin"), File("../shared-regex/src/commonMain/kotlin"),
            File("../predicates/src/commonMain/kotlin"))
        val sources = roots.flatMap { root -> root.walk().filter { it.extension == "kt" }.map { root to it } }
        assertTrue(sources.size > 100, "scanned ${sources.size} files under ${roots.map { it.absolutePath }}")
        val hits = sources.flatMap { (root, file) ->
            file.readLines().withIndex()
                .filter { (_, line) -> !line.trimStart().startsWith("*") && !line.trimStart().startsWith("//") }
                .filter { (_, line) -> CLASSIFIER.containsMatchIn(line.substringBefore("//")) }
                .map { (at, line) -> "${file.relativeTo(root)}:${at + 1}: ${line.trim()}" }
        }
        assertEquals(emptyList(), hits)
    }

    /** The pattern catches each spelling it names, so an empty scan is evidence rather than a mismatch. */
    @Test
    fun theScanCatchesEveryClassifierSpelling() {
        val planted = listOf("c.isDigit()", "s.isBlank()", "s?.isNullOrBlank()", "s.isNotBlank()", "s.ifBlank { x }",
            "s.trim()", "s.trimEnd()", "map(String::trim)", "if (isLetterOrDigit()) x", "s.toIntOrNull()",
            "s.toLongOrNull()", "a.equals(b, ignoreCase = true)", "p.doubleOrNull", "p.floatOrNull", "s.lowercase()",
            "s.uppercase()", "c.lowercaseChar()", "c.uppercaseChar()", "a.equals(b, true)", "s.startsWith(\"x\", true)",
            "s.endsWith(p, true)", "Regex(p, RegexOption.IGNORE_CASE)")
        val missed = planted.filterNot { CLASSIFIER.containsMatchIn(it) }
        assertEquals(emptyList(), missed)
        assertEquals(emptyList(), listOf("s.trim { it <= ' ' }", "s.isA12Blank()", "s.javaTrimmed()", "a.equals(b)", "s.startsWith(\"x\")").filter { CLASSIFIER.containsMatchIn(it) })
    }

    private companion object {
        /** A platform classifier call, a blank test, or an argument-free trim; a trim with a predicate is A12's own. */
        val CLASSIFIER = Regex(
            """\.(isDigit|isLetter|isLetterOrDigit|isWhitespace|digitToInt|digitToIntOrNull)\(""" +
                """|Char::(isDigit|isLetter|isLetterOrDigit|isWhitespace)\b""" +
                """|(?<![\w.])(isDigit|isLetter|isLetterOrDigit|isWhitespace)\(\)""" +
                """|\.(isBlank|isNotBlank|isNullOrBlank)\(|\.ifBlank\b|\.(trim|trimStart|trimEnd)\(\)""" +
                """|String::(isBlank|isNotBlank|trim|trimStart|trimEnd)\b""" +
                """|\.(doubleOrNull|floatOrNull)\b|\.(lowercase|uppercase|lowercaseChar|uppercaseChar|titlecase|titlecaseChar)\(\)""" +
                """|\.(equals|startsWith|endsWith|contains|compareTo|regionMatches)\([^()]*,\s*true\s*\)|RegexOption\.IGNORE_CASE""" +
                """|\.(toIntOrNull|toLongOrNull|toDoubleOrNull|toFloatOrNull|toShortOrNull|toByteOrNull)\(|ignoreCase\s*=\s*true""",
        )
    }
}
