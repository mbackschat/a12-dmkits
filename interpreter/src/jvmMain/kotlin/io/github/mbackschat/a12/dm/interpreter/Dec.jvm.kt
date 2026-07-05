package io.github.mbackschat.a12.dm.interpreter

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/**
 * JVM decimal actual — BigDecimal, with the kernel's scale-19 comparison pre-rounding ([scale19]) and its
 * `MathContext(50)` HALF_UP arithmetic cap (`VkBigDecimal.INTERNAL_PRECISION`) on `×`/`÷`/`^` — bit-for-bit
 * with the kernel: `+`/`-` are exact (the kernel does not cap them), while a product/quotient/power is rounded
 * to 50 significant digits (observable only beyond 50, e.g. a 26-digit × 26-digit → 52-digit product).
 */
actual class Dec(val v: BigDecimal) : Comparable<Dec> {
    actual operator fun plus(other: Dec): Dec = Dec(v + other.v)
    actual operator fun minus(other: Dec): Dec = Dec(v - other.v)
    actual operator fun times(other: Dec): Dec = Dec(v.multiply(other.v, INTERNAL))
    actual fun div(other: Dec): Dec = Dec(v.divide(other.v, INTERNAL))
    actual fun pow(exponent: Int): Dec =
        if (exponent >= 0) Dec(v.pow(exponent, INTERNAL))
        else Dec(BigDecimal.ONE.divide(v.pow(-exponent, INTERNAL), INTERNAL))
    actual override fun compareTo(other: Dec): Int = v.compareTo(other.v)
    actual fun abs(): Dec = Dec(v.abs())
    actual fun scale19(): Dec = Dec(v.setScale(19, RoundingMode.HALF_UP))
    actual fun compareScaled19(other: Dec): Int =
        // Fast path: at ≤19 fractional digits, setScale(19, HALF_UP) only appends trailing zeros (it never
        // rounds away a significant digit), and BigDecimal.compareTo ignores scale — so the rounded compare
        // equals the raw compare. Skip the two setScale allocations. Beyond 19 digits the rounding can change
        // the value, so fall back to the explicit form (identical to scale19().compareTo(scale19())).
        if (v.scale() <= 19 && other.v.scale() <= 19) v.compareTo(other.v)
        else v.setScale(19, RoundingMode.HALF_UP).compareTo(other.v.setScale(19, RoundingMode.HALF_UP))
    actual fun roundTo(places: Int, mode: RoundMode): Dec {
        // The kernel's two-step BigDecimalUtils.runden: pre-round to DEFAULT_SCALE (19) HALF_UP, then to places.
        val pre = v.setScale(19, RoundingMode.HALF_UP)
        val jmode = when (mode) {
            RoundMode.FLOOR -> RoundingMode.FLOOR
            RoundMode.CEILING -> RoundingMode.CEILING
            RoundMode.HALF_UP -> RoundingMode.HALF_UP
        }
        return Dec(pre.setScale(places, jmode))
    }
    actual fun display(): String = v.stripTrailingZeros().toPlainString()

    // VALUE equality, scale-INSENSITIVE — consistent with [compareTo] (and `BigDecimal.compareTo`, which the
    // interpreter uses everywhere numbers are compared), NOT `BigDecimal.equals` (scale-sensitive) nor object
    // identity. So `2.0 == 2.00`, and the data classes holding a Dec (Val.Num, Expr.NumLit) get correct equality.
    // hashCode keys off the canonical (trailing-zero-stripped) form, so equal values hash the same (IF41).
    override fun equals(other: Any?): Boolean = other is Dec && v.compareTo(other.v) == 0
    override fun hashCode(): Int = v.stripTrailingZeros().toPlainString().hashCode()
}

/** The kernel's `VkBigDecimal.INTERNAL_PRECISION` — 50 significant digits, HALF_UP — for `×`, `÷` and `^`. */
private val INTERNAL = MathContext(50, RoundingMode.HALF_UP)

actual fun dec(value: String): Dec = Dec(BigDecimal(value))
actual fun decOrNull(value: String): Dec? = try { Dec(BigDecimal(value)) } catch (e: NumberFormatException) { null }
actual fun dec(value: Int): Dec = Dec(BigDecimal(value))
actual val DEC_ZERO: Dec = Dec(BigDecimal.ZERO)
