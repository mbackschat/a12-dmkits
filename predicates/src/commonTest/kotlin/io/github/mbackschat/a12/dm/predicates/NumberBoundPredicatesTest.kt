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

    private companion object {
        val INVERTED = setOf(
            "1.5 over 1.4, f0", "1.6 over 1, f0", "2.5 over 2, f0",
            "1.04 over 1, f1", "1.004 over 1.001, f1", "1.4 over 1, f-1",
        )
    }
}
