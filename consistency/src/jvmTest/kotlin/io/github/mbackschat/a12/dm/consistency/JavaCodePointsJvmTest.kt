package io.github.mbackschat.a12.dm.consistency

import java.util.regex.Pattern
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [isUnicodeOther] against the implementation it spells out, `\p{C}` compiled by `java.util.regex` exactly as
 * A12's `FehlertextUtil.CONTROL_CHARACTER_PATTERN` is, over every code point.
 *
 * The regex rather than `Character.getType`, because the regex is what A12 runs. JVM-only for the same reason
 * as `A12BlankJvmTest`: only this target can ask the JDK, and the JS checker answers from the table this test
 * holds to it. On a mismatch the message carries the table regenerated from this JDK, which is the only way
 * [OTHER_RUNS] is ever written.
 */
class JavaCodePointsJvmTest {

    private val other = Pattern.compile("\\p{C}")

    private fun regexSays(codePoint: Int) = other.matcher(String(Character.toChars(codePoint))).find()

    @Test
    fun theSpelledOutSetIsTheJdkRegexsOwn() {
        val disagreeing = (0..0x10FFFF).filter { isUnicodeOther(it) != regexSays(it) }
        assertEquals(
            emptyList(),
            disagreeing.take(20).map { "U+" + it.toString(16).uppercase() },
            "code points where OTHER_RUNS and \\p{C} on JDK ${System.getProperty("java.version")} differ; " +
                "regenerated:\n" + regenerated(),
        )
    }

    /** Against `String.codePoints`, the JDK's own reading: a pair is one code point, a lone surrogate itself. */
    @Test
    fun codePointsAreTheJdksReading() {
        val text = "a\uD83D\uDE00\uD83Db\uDE00\uDB40\uDC01"
        assertEquals(text.codePoints().toArray().toList(), text.javaCodePoints())
    }

    private fun regenerated(): String {
        val runs = mutableListOf<String>()
        var start = -1
        for (codePoint in 0..0x110000) {
            val inside = codePoint <= 0x10FFFF && regexSays(codePoint)
            if (inside && start < 0) start = codePoint
            if (!inside && start >= 0) {
                val last = codePoint - 1
                runs += if (start == last) hex(start) else hex(start) + "-" + hex(last)
                start = -1
            }
        }
        return runs.joinToString(" ")
    }

    private fun hex(codePoint: Int) = codePoint.toString(16).uppercase()
}
