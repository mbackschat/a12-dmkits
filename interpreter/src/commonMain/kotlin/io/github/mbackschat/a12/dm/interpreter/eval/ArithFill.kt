package io.github.mbackschat.a12.dm.interpreter.eval

import io.github.mbackschat.a12.dm.interpreter.DEC_ZERO
import io.github.mbackschat.a12.dm.interpreter.Dec
import io.github.mbackschat.a12.dm.interpreter.dec

/**
 * Pure arithmetic FILL propagation — how a comparison operand's directional fillability ([Fill], the kernel's
 * `kannGroesserWerden`/`kannKleinerWerden` bits) flows through `+`, `−`, `×`, `÷`, `^`. Strategy-agnostic
 * runtime semantics (the IF10 *runtime-vs-strategy* split, sibling of [Polarity]/[Aggregates]): the strategy
 * RESOLVES each operand to a [Fill] plus its value (`Interpreter.fill`/`evalExpr`) and passes them here; these
 * apply the per-operator propagation rule, then [Polarity] types the fired comparison. A future generator emits
 * the same calls.
 *
 * Clean-room from the kernel's `VkBigDecimal` (its `add`/`subtract`/`multiply`/`divide`/`power` +
 * `produktKannGroesser/KleinerWerden`/`powCanChange`) — the *mechanism*, not the source expression. The kernel
 * carries value + fill together on one object; the interpreter keeps value resolution in the strategy and passes
 * the resolved value SIGN (for `×`/`÷`) or the value itself (for `^`, whose direction depends on the base's
 * magnitude and the exponent's parity).
 */
object ArithFill {

    /** `a + b`: each direction is fillable iff EITHER operand is fillable in that direction. */
    fun add(a: Fill, b: Fill): Fill = Fill.of(a.canGrow || b.canGrow, a.canShrink || b.canShrink)

    /** `a − b`: the subtrahend's direction FLIPS — the result grows if `a` grows or `b` shrinks, shrinks if
     *  `a` shrinks or `b` grows. */
    fun subtract(a: Fill, b: Fill): Fill = Fill.of(a.canGrow || b.canShrink, a.canShrink || b.canGrow)

    /**
     * `a × b` — SIGN-DEPENDENT (`produktKannGroesser/KleinerWerden`). A factor pushes the product UP by growing
     * when the other factor is positive / shrinking when the other is negative, plus the both-grow / both-shrink
     * cross terms. [aSign]/[bSign] are each operand's resolved value sign (−1 / 0 / +1).
     */
    fun multiply(a: Fill, aSign: Int, b: Fill, bSign: Int): Fill =
        Fill.of(productGrows(a, aSign, b, bSign), productShrinks(a, aSign, b, bSign))

    /** `a ÷ b = a × (1/b)`: the reciprocal has b's sign and the flipped fillability of `1/b`. */
    fun divide(a: Fill, aSign: Int, b: Fill, bSign: Int): Fill {
        val recip = reciprocal(b, bSign)
        return Fill.of(productGrows(a, aSign, recip, bSign), productShrinks(a, aSign, recip, bSign))
    }

    private fun productGrows(a: Fill, aSign: Int, b: Fill, bSign: Int): Boolean =
        (a.canGrow && b.canGrow) || (a.canShrink && b.canShrink) ||
            (bSign > 0 && a.canGrow) || (bSign < 0 && a.canShrink) ||
            (aSign > 0 && b.canGrow) || (aSign < 0 && b.canShrink)

    private fun productShrinks(a: Fill, aSign: Int, b: Fill, bSign: Int): Boolean =
        (a.canGrow && b.canShrink) || (a.canShrink && b.canGrow) ||
            (bSign > 0 && a.canShrink) || (bSign < 0 && a.canGrow) ||
            (aSign > 0 && b.canShrink) || (aSign < 0 && b.canGrow)

    /** `1/d`: grows when `d` shrinks (or `d < 0` and grows toward 0); shrinks when `d` grows (or `d > 0` and
     *  shrinks toward 0). Its sign equals `d`'s (so the caller passes [dSign] on as the reciprocal's sign). */
    private fun reciprocal(d: Fill, dSign: Int): Fill =
        Fill.of(d.canShrink || (dSign < 0 && d.canGrow), d.canGrow || (dSign > 0 && d.canShrink))

    /**
     * `base ^ exp` — `VkBigDecimal.power` + `powCanChange`. The kernel's quiet-invalid cases (`0` to a negative
     * power, `|exp| > 1000`, a fractional exponent) carry a fixed value → [FIXED]; a negative exponent recurses
     * as `(1/base)^(−exp)`. Otherwise the direction depends on the base's magnitude relative to 0 / 1 / −1 and
     * the exponent's parity.
     */
    fun power(baseFill: Fill, baseVal: Dec, expFill: Fill, expVal: Dec): Fill {
        if (expVal.compareTo(DEC_ZERO) < 0) {
            if (baseVal.compareTo(DEC_ZERO) == 0) return FIXED   // 0 ^ -n is invalid
            // (1/base) ^ (-exp): the reciprocal of the base, the exponent negated (its fill directions flip)
            return power(reciprocal(baseFill, baseVal.compareTo(DEC_ZERO)), ONE.div(baseVal),
                Fill.of(expFill.canShrink, expFill.canGrow), DEC_ZERO.minus(expVal))
        }
        if (expVal.compareTo(ONE_THOUSAND) > 0) return FIXED     // over the kernel's exponent cap
        if (!isWhole(expVal)) return FIXED                       // a fractional exponent is invalid
        return powChange(baseFill, baseVal, expFill, expVal)
    }

    private fun powChange(baseFill: Fill, baseVal: Dec, expFill: Fill, expVal: Dec): Fill = when {
        !expFill.canGrow && !expFill.canShrink -> powFixedExponent(baseFill, baseVal, expVal)   // exponent fixed
        !baseFill.canGrow && !baseFill.canShrink -> powFixedBase(baseVal, expFill, expVal)      // base fixed
        // everything variable: only base > 1 with neither able to shrink is one-directional; otherwise both.
        baseVal.compareTo(ONE) > 0 && !expFill.canShrink && !baseFill.canShrink -> GROW_ONLY
        else -> BOTH
    }

    /** Fixed exponent (≥ 0), variable base. `x^0 = 1` and a fixed base are constant; else a square grows on both
     *  signs of the base and shrinks toward 0 (kernel `powCanChangeWithFixedExponent`). */
    private fun powFixedExponent(base: Fill, baseVal: Dec, expVal: Dec): Fill {
        if (expVal.compareTo(DEC_ZERO) == 0) return FIXED
        if (!base.canGrow && !base.canShrink) return FIXED
        val even = isEven(expVal)
        val canGrow = base.canGrow || even
        val canShrink = (base.canShrink && (!even || baseVal.compareTo(DEC_ZERO) > 0)) ||
            (even && baseVal.compareTo(DEC_ZERO) < 0 && base.canGrow)
        return Fill.of(canGrow, canShrink)
    }

    /** Fixed base value, variable exponent (≥ 0). Monotone rising for base > 1, falling for 0 < base < 1,
     *  constant at base == 1; the zero-base and negative-base cases split further (kernel
     *  `powCanChangeWithFixedBase`). */
    private fun powFixedBase(baseVal: Dec, exp: Fill, expVal: Dec): Fill = when {
        baseVal.compareTo(ONE) > 0 -> exp                                     // base > 1: rising in exp
        baseVal.compareTo(ONE) == 0 -> FIXED                                  // 1 ^ n = 1
        baseVal.compareTo(DEC_ZERO) > 0 -> Fill.of(exp.canShrink, exp.canGrow) // 0 < base < 1: falling in exp
        baseVal.compareTo(DEC_ZERO) == 0 -> powFixedZeroBase(exp, expVal)
        else -> powFixedNegativeBase(baseVal, exp, expVal)
    }

    /** base == 0, variable exponent (≥ 0): `0^n = 0` for n > 0 (constant); n reaching 0 gives `0^0 = 1` (can
     *  shrink back to 0); a shrinkable exponent can dive toward 0⁻ⁿ → ∞ (can grow). */
    private fun powFixedZeroBase(exp: Fill, expVal: Dec): Fill = when {
        !exp.canShrink && expVal.compareTo(DEC_ZERO) > 0 -> FIXED
        !exp.canShrink && expVal.compareTo(DEC_ZERO) == 0 -> SHRINK_ONLY
        else -> GROW_ONLY
    }

    /** base < 0, variable exponent (≥ 0): the result alternates sign with the exponent's parity; the magnitude
     *  rises for base < −1 and falls for −1 < base < 0 (kernel `powCanChangeWithFixedNegativeBase`). */
    private fun powFixedNegativeBase(baseVal: Dec, exp: Fill, expVal: Dec): Fill {
        val even = isEven(expVal)
        if (baseVal.compareTo(MINUS_ONE) > 0) return when {   // -1 < base < 0
            even && !exp.canShrink -> SHRINK_ONLY
            !even && !exp.canShrink -> GROW_ONLY
            else -> BOTH
        }
        if (baseVal.compareTo(MINUS_ONE) == 0) return if (even) SHRINK_ONLY else GROW_ONLY   // (-1)^n alternates
        return when {   // base < -1
            even && !exp.canGrow -> SHRINK_ONLY
            !even && !exp.canGrow -> GROW_ONLY
            else -> BOTH
        }
    }

    private fun isWhole(v: Dec): Boolean = v.compareTo(dec(v.display().substringBefore('.'))) == 0
    private fun isEven(v: Dec): Boolean = (v.display().toIntOrNull() ?: 0) % 2 == 0

    private val FIXED = Fill.of(canGrow = false, canShrink = false)
    private val GROW_ONLY = Fill.of(canGrow = true, canShrink = false)
    private val SHRINK_ONLY = Fill.of(canGrow = false, canShrink = true)
    private val BOTH = Fill.of(canGrow = true, canShrink = true)
    private val ONE = dec(1)
    private val MINUS_ONE = dec(-1)
    private val ONE_THOUSAND = dec(1000)
}
