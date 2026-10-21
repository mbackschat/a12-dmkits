package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals

/** [JavaDigits] and [javaLongOrNull] against the JDK calls they spell out. */
class JavaDigitsJvmTest {

    @Test
    fun theTableIsTheJdksOverTheWholeBmp() {
        val wrong = (0..0xFFFF).map(Int::toChar).filter { JavaDigits.valueOf(it) != Character.digit(it, 10) }
        assertEquals(emptyList(), wrong.map { "U+%04X".format(it.code) })
    }

    @Test
    fun javaLongIsLongParseLong() {
        val texts = listOf("", "+", "-", "0", "-0", "+7", "42", "007", " 1", "\u0663", "-\u0661\u0662", "\uFF15", "1.0",
            "9223372036854775807", "9223372036854775808", "-9223372036854775808", "-9223372036854775809", "--1")
        assertEquals(texts.map { runCatching { java.lang.Long.parseLong(it) }.getOrNull() }, texts.map { it.javaLongOrNull() })
    }
}
