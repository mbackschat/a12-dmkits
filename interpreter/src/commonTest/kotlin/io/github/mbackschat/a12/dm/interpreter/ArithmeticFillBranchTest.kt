package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.ArithFill
import io.github.mbackschat.a12.dm.interpreter.eval.Fill
import kotlin.test.Test
import kotlin.test.assertEquals

/** Pure JVM+JS lock for both directional bits of the kernel's conservative arithmetic-fill dispatch. */
class ArithmeticFillBranchTest {

    @Test
    fun multiplicationIncludesJointTermsEvenWhenBothCurrentSignsAreZero() {
        assertFill(grow = true, shrink = false, ArithFill.multiply(GROW, 0, GROW, 0))
        assertFill(grow = true, shrink = false, ArithFill.multiply(SHRINK, 0, SHRINK, 0))
        assertFill(grow = false, shrink = true, ArithFill.multiply(GROW, 0, SHRINK, 0))
    }

    @Test
    fun divisionUsesTheReciprocalDirectionsInBothDirections() {
        assertFill(grow = false, shrink = true, ArithFill.divide(FIXED, 1, GROW, 1))
        assertFill(grow = true, shrink = true, ArithFill.divide(FIXED, 1, BOTH, 1))
        assertFill(grow = true, shrink = false, ArithFill.divide(FIXED, 1, SHRINK, -1))
    }

    @Test
    fun fixedExponentAndFixedBaseRegionsRemainDistinct() {
        assertFill(grow = false, shrink = false, ArithFill.power(BOTH, dec(2), FIXED, dec(0)))
        assertFill(grow = false, shrink = false, ArithFill.power(FIXED, dec(1), BOTH, dec(2)))
        assertFill(grow = false, shrink = true, ArithFill.power(FIXED, dec("0.5"), GROW, dec(2)))
        assertFill(grow = false, shrink = false, ArithFill.power(FIXED, dec(0), GROW, dec(2)))
        assertFill(grow = false, shrink = true, ArithFill.power(FIXED, dec(0), GROW, dec(0)))
        assertFill(grow = true, shrink = false, ArithFill.power(FIXED, dec(0), BOTH, dec(0)))
        assertFill(grow = false, shrink = true, ArithFill.power(FIXED, dec(-1), GROW, dec(2)))
        assertFill(grow = true, shrink = false, ArithFill.power(FIXED, dec(-1), GROW, dec(3)))
    }

    @Test
    fun negativePowerStagesReciprocalAndSwapsExponentDirections() {
        assertFill(grow = false, shrink = true, ArithFill.power(FIXED, dec("0.5"), GROW, dec(-2)))
    }

    @Test
    fun bothVariableSpecialCaseAndInvalidDomainsStayConservative() {
        assertFill(grow = true, shrink = false, ArithFill.power(GROW, dec(2), GROW, dec(2)))
        assertFill(grow = false, shrink = false, ArithFill.power(FIXED, dec(0), FIXED, dec(-1)))
        assertFill(grow = false, shrink = false, ArithFill.power(FIXED, dec(2), FIXED, dec(1001)))
        assertFill(grow = false, shrink = false, ArithFill.power(FIXED, dec(2), FIXED, dec("0.5")))
    }

    private fun assertFill(grow: Boolean, shrink: Boolean, actual: Fill) {
        assertEquals(grow, actual.canGrow, "canGrow")
        assertEquals(shrink, actual.canShrink, "canShrink")
    }

    private companion object {
        val FIXED = Fill.of(canGrow = false, canShrink = false)
        val GROW = Fill.of(canGrow = true, canShrink = false)
        val SHRINK = Fill.of(canGrow = false, canShrink = true)
        val BOTH = Fill.of(canGrow = true, canShrink = true)
    }
}
