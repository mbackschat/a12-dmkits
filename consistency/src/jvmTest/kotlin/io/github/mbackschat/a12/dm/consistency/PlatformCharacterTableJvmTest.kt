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
 */
class PlatformCharacterTableJvmTest {

    @Test
    fun sharedCodeClassifiesNoCharacterThroughAPlatformTable() {
        val classifier = Regex("""\.(isDigit|isLetter|isLetterOrDigit|isWhitespace|digitToInt|digitToIntOrNull)\(|Char::(isDigit|isLetter|isLetterOrDigit|isWhitespace)\b""")
        val roots = listOf(File("src/commonMain/kotlin"), File("../shared-regex/src/commonMain/kotlin"))
        val sources = roots.flatMap { root -> root.walk().filter { it.extension == "kt" }.map { root to it } }
        assertTrue(sources.size > 100, "scanned ${sources.size} files under ${roots.map { it.absolutePath }}")
        val hits = sources.flatMap { (root, file) ->
            file.readLines().withIndex()
                .filter { (_, line) -> !line.trimStart().startsWith("*") && !line.trimStart().startsWith("//") }
                .filter { (_, line) -> classifier.containsMatchIn(line.substringBefore("//")) }
                .map { (at, line) -> "${file.relativeTo(root)}:${at + 1}: ${line.trim()}" }
        }
        assertEquals(emptyList(), hits)
    }
}
