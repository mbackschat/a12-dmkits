package io.github.mbackschat.a12.dm.interpreter.eval

import io.github.mbackschat.a12.dm.interpreter.Dec

/** Pure runtime semantics shared by tolerance truth and polarity. */
internal object ToleranceOps {

    /** Kernel order: normalize each operand to scale 19, then subtract and compare with the strict band. */
    fun differs(left: Dec, right: Dec, band: Dec): Boolean =
        (left.scale19() - right.scale19()).abs().compareTo(band.scale19()) > 0

    /** A firing tolerance leaf uses the same directional movement rule as numeric inequality. */
    fun fillable(left: Dec, right: Dec, leftFill: Fill, rightFill: Fill): Boolean =
        Polarity.neFillable(left.compareScaled19(right) < 0, leftFill, rightFill)
}
