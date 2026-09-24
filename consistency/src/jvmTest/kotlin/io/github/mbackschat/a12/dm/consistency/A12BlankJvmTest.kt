package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [isJavaWhitespace] against the implementation it spells out, `Character.isWhitespace`, over every `char`.
 *
 * The oracle is the JDK itself rather than its javadoc, because the set follows the Unicode version the JDK
 * carries: U+180E left the space separators in Unicode 6.3, and a hand-derived list is exactly where such a
 * move goes unnoticed. JVM-only for the same reason — only this target can ask the JDK — while the predicate is
 * common code, so the JS checker answers from the list this test holds to it.
 */
class A12BlankJvmTest {

    @Test
    fun theSpelledOutSetIsTheJdksOwn() {
        val disagreeing = (Char.MIN_VALUE..Char.MAX_VALUE)
            .filter { it.isJavaWhitespace() != Character.isWhitespace(it) }
            .map { "U+" + it.code.toString(16).uppercase().padStart(4, '0') }
        assertEquals(emptyList(), disagreeing, "chars where the spelled-out set and Character.isWhitespace differ")
    }
}
