package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A Number bound's printed form and digit limit against the rows A12 was measured on, on both checker runtimes.
 */
class PrintedNumberBoundTest {

    /**
     * The printed bound's two formal checks against the rows A12 was measured on: a bound that prints as `-0`
     * at scale 0, and a printed form of more than 15 digits, counted with the leading `0` and without the sign,
     * on the value rounded at scale 0 and on its shortest decimal otherwise.
     */
    @Test
    fun aPrintedBoundFailsOnASignedZeroOrMoreThanFifteenDigits() {
        val rows = linkedMapOf(
            "-0.4, f0" to printedNumberBoundDefects(-0.4, 0),
            "-0.001, absent" to printedNumberBoundDefects(-0.001, null),
            "-0.5, f0" to printedNumberBoundDefects(-0.5, 0),
            "-0.0, f0" to printedNumberBoundDefects(-0.0, 0),
            "-0.4, f1" to printedNumberBoundDefects(-0.4, 1),
            "-0.04, f-1" to printedNumberBoundDefects(-0.04, -1),
            "999999999999999, f0" to printedNumberBoundDefects(999999999999999.0, 0),
            "999999999999999.4, f0" to printedNumberBoundDefects(999999999999999.4, 0),
            "999999999999999.6, f0" to printedNumberBoundDefects(999999999999999.6, 0),
            "999999999999999.4, f-1" to printedNumberBoundDefects(999999999999999.4, -1),
            "1e15, f0" to printedNumberBoundDefects(1e15, 0),
            "-1e15, f1" to printedNumberBoundDefects(-1e15, 1),
            "1e14, f1" to printedNumberBoundDefects(1e14, 1),
            "0.12345678901234, f1" to printedNumberBoundDefects(0.12345678901234, 1),
            "0.123456789012345, f1" to printedNumberBoundDefects(0.123456789012345, 1),
            "-0.123456789012345, f14" to printedNumberBoundDefects(-0.123456789012345, 14),
            "1.12345678901234, f-1" to printedNumberBoundDefects(1.12345678901234, -1),
            "1.123456789012345, f-1" to printedNumberBoundDefects(1.123456789012345, -1),
            "12345678901234.56, f1" to printedNumberBoundDefects(12345678901234.56, 1),
            "0.000000000000001, f1" to printedNumberBoundDefects(0.000000000000001, 1),
            "1e-20, f1" to printedNumberBoundDefects(1e-20, 1),
            "1.5e-7, f1" to printedNumberBoundDefects(1.5e-7, 1),
            "0.30000000000000004, f1" to printedNumberBoundDefects(0.30000000000000004, 1),
            "100.10000000000001, f14" to printedNumberBoundDefects(100.10000000000001, 14),
            "1, f14" to printedNumberBoundDefects(1.0, 14),
        )
        val signedZero = setOf("-0.4, f0", "-0.001, absent")
        val tooLong = setOf(
            "999999999999999.6, f0", "999999999999999.4, f-1", "1e15, f0", "-1e15, f1", "0.123456789012345, f1",
            "-0.123456789012345, f14", "1.123456789012345, f-1", "12345678901234.56, f1", "0.000000000000001, f1",
            "1e-20, f1", "0.30000000000000004, f1", "100.10000000000001, f14",
        )
        val expected = rows.keys.associateWith { row ->
            setOfNotNull(PrintedBoundDefect.SIGNED_ZERO.takeIf { row in signedZero },
                PrintedBoundDefect.TOO_MANY_DIGITS.takeIf { row in tooLong })
        }
        assertEquals(expected, rows)
    }
}
