package io.github.mbackschat.a12.dm.interpreter.eval

import io.github.mbackschat.a12.dm.interpreter.dec
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Cross-platform root lock for the kernel's tolerance primitive: normalize both operands independently to
 * scale 19 before subtracting, and type a firing through the same directional movement rule as `!=`.
 */
class ToleranceOpsTest {

    @Test
    fun normalizesOperandsBeforeSubtracting() {
        val epsilon = "0.000000000000000000049"
        assertFalse(
            ToleranceOps.differs(
                dec("1.000000000000000000049"),
                dec("-$epsilon"),
                dec("1"),
            ),
            "both operands round independently to 1 and 0; rounding their completed difference would be wrong",
        )
    }

    @Test
    fun unsignedEmptyMovementIsDirectional() {
        val unsignedEmpty = Fill.of(canGrow = true, canShrink = false)
        val fixed = Fill.of(canGrow = false, canShrink = false)

        assertTrue(
            ToleranceOps.fillable(dec("0"), dec("2"), unsignedEmpty, fixed),
            "0 < 2: growing the empty unsigned operand can close the band",
        )
        assertFalse(
            ToleranceOps.fillable(dec("0"), dec("-2"), unsignedEmpty, fixed),
            "0 > -2: the unsigned empty operand cannot shrink toward the other side",
        )
    }
}
