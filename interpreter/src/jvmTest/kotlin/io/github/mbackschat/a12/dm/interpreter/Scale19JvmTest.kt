package io.github.mbackschat.a12.dm.interpreter

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [Dec.scale19] may return a value within 19 fractional digits unchanged, so every reading taken through it must
 * equal the reading of the explicitly padded `setScale(19, HALF_UP)` form the kernel computes. The JDK is the oracle.
 */
class Scale19JvmTest {

    @Test
    fun everyValueReadingThroughScale19IsThePaddedFormsReading() {
        val texts = listOf("0", "17", "-17", "100", "0.93", "0.92", "1E+3", "12.500", "-0.0010", "9223372036854775807",
            "123456789012345678.1234567890123456789", "0.1234567890123456789", "0.12345678901234567895",
            "-2.00000000000000000005", "1E-19", "1E-20", "5E-20")
        val wrong = texts.filter { text ->
            val ours = Dec(BigDecimal(text)).scale19()
            val padded = Dec(BigDecimal(text).setScale(19, RoundingMode.HALF_UP))
            ours != padded || ours.fractionalDigits() != padded.fractionalDigits() ||
                ours.strippedPrecision() != padded.strippedPrecision() || ours.display() != padded.display() ||
                ours.displayAtScale(4) != padded.displayAtScale(4) || ours.hashCode() != padded.hashCode()
        }
        assertEquals(emptyList(), wrong)
    }
}
