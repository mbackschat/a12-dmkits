package io.github.mbackschat.a12.dm.interpreter.eval

import io.github.mbackschat.a12.dm.interpreter.ast.CmpOp

/**
 * Directional fillability of a comparison operand — whether continuing to fill could grow / shrink its value
 * (the kernel's `NumberCombiner` `kannGroesserWerden`/`kannKleinerWerden` direction bits). A pure value object.
 *
 * **Interned** (2 × 2 = 4 instances) — the kernel packs these as booleans on the value (no separate object);
 * a `Fill` is allocated per `Cmp` operand in the hot lazy-polarity walk, so always use [of], never the
 * constructor.
 */
data class Fill(val canGrow: Boolean, val canShrink: Boolean) {
    companion object {
        private val FF = Fill(false, false); private val FT = Fill(false, true)
        private val TF = Fill(true, false); private val TT = Fill(true, true)
        fun of(canGrow: Boolean, canShrink: Boolean): Fill =
            if (canGrow) (if (canShrink) TT else TF) else (if (canShrink) FT else FF)
    }
}

/**
 * Pure comparison-**polarity** rules — the kernel's `BedingungsOperatorHelper.vergleiche` "is this firing
 * fillable?" decision over two operand [Fill]s (VALUE if no fill could clear it, OMISSION if one could).
 * Strategy-agnostic runtime semantics (the IF10 *runtime-vs-strategy* split, sibling of [Aggregates]/[ValCompare]):
 * the strategy RESOLVES each operand to a [Fill] (ctx-coupled — `Interpreter.fill`/`funcFill`) and passes them
 * here; these apply the per-operator rule. A future generator emits the same calls.
 */
object Polarity {

    /**
     * Whether filling an operand *in the breaking direction* could clear the firing, for GT/GE/LT/LE/EQ:
     * GT/GE break when the left shrinks or the right grows; LT/LE the mirror; EQ when either side can move.
     * NE is ordering-dependent — see [neFillable]; this returns the symmetric [anyMove] for NE as a safe
     * fallback (the strategy calls [neFillable] when the operands' ordering is known).
     */
    fun fillable(op: CmpOp, l: Fill, r: Fill): Boolean = when (op) {
        CmpOp.GT, CmpOp.GE -> l.canShrink || r.canGrow
        CmpOp.LT, CmpOp.LE -> l.canGrow || r.canShrink
        CmpOp.EQ -> anyMove(l, r)
        CmpOp.NE -> anyMove(l, r)
    }

    /** `!=` given the resolved ordering ([leftLessThanRight]): left < right breaks when left grows or right
     *  shrinks; left > right is the mirror (kernel `getFehlerFuerNE`). Meaningful only when the rule fires
     *  (both operands known); the strategy supplies the ordering. */
    fun neFillable(leftLessThanRight: Boolean, l: Fill, r: Fill): Boolean =
        if (leftLessThanRight) l.canGrow || r.canShrink else l.canShrink || r.canGrow

    /** EQ-like fillability: either operand can move at all. */
    fun anyMove(l: Fill, r: Fill): Boolean = l.canGrow || l.canShrink || r.canGrow || r.canShrink
}
