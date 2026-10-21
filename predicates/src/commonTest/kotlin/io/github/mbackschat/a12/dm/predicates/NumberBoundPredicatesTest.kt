package io.github.mbackschat.a12.dm.predicates

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The bound law against the rows A12 was measured on (`adapter.laws.CheckerDeclarationAdmissionAgreementTest`),
 * so each rival rounding named on [numberBoundsInverted] fails here on both runtimes, not only on the JVM.
 */
class NumberBoundPredicatesTest {

    @Test
    fun theBoundsAreRoundedOnlyWhenNoFractionIsDeclared() {
        val rows = linkedMapOf(
            "1.4 over 1, f0" to numberBoundsInverted(1.4, 1.0, 0),
            "1.4 over 1.2, f0" to numberBoundsInverted(1.4, 1.2, 0),
            "2.4 over 1.6, f0" to numberBoundsInverted(2.4, 1.6, 0),
            "-1.5 over -1.6, f0" to numberBoundsInverted(-1.5, -1.6, 0),
            "-0.5 over -1, f0" to numberBoundsInverted(-0.5, -1.0, 0),
            "0.49999999999999994 over 0, f0" to numberBoundsInverted(0.49999999999999994, 0.0, 0),
            "1.4 over 1, absent" to numberBoundsInverted(1.4, 1.0, null),
            "1 over 1, f0" to numberBoundsInverted(1.0, 1.0, 0),
            "1.5 over 1.4, f0" to numberBoundsInverted(1.5, 1.4, 0),
            "1.6 over 1, f0" to numberBoundsInverted(1.6, 1.0, 0),
            "2.5 over 2, f0" to numberBoundsInverted(2.5, 2.0, 0),
            "1.04 over 1, f1" to numberBoundsInverted(1.04, 1.0, 1),
            "1.004 over 1.001, f1" to numberBoundsInverted(1.004, 1.001, 1),
            "1.4 over 1, f-1" to numberBoundsInverted(1.4, 1.0, -1),
        )
        val expected = rows.keys.associateWith { it in INVERTED }
        assertEquals(expected, rows)
    }

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

    private companion object {
        val INVERTED = setOf(
            "1.5 over 1.4, f0", "1.6 over 1, f0", "2.5 over 2, f0",
            "1.04 over 1, f1", "1.004 over 1.001, f1", "1.4 over 1, f-1",
        )
    }
}
