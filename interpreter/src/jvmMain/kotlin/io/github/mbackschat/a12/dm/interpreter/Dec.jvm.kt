package io.github.mbackschat.a12.dm.interpreter

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/**
 * JVM decimal actual — full-precision BigDecimal, with the kernel's scale-19 comparison pre-rounding
 * ([scale19]). Still NOT bit-for-bit on ARITHMETIC: the kernel caps `×`/`÷`/`^` at `MathContext(50)` HALF_UP
 * (`VkBigDecimal.INTERNAL_PRECISION`); `times` here is full-precision (diverges only beyond 50 significant
 * digits — deferred to the `÷`/`^` increment, where the MathContext is mandatory). Compare/equality now match.
 */
actual class Dec(val v: BigDecimal) : Comparable<Dec> {
    actual operator fun plus(other: Dec): Dec = Dec(v + other.v)
    actual operator fun minus(other: Dec): Dec = Dec(v - other.v)
    actual operator fun times(other: Dec): Dec = Dec(v * other.v)
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
}

/** The kernel's `VkBigDecimal.INTERNAL_PRECISION` — 50 significant digits, HALF_UP — for ÷ and ^. */
private val INTERNAL = MathContext(50, RoundingMode.HALF_UP)

actual fun dec(value: String): Dec = Dec(BigDecimal(value))
actual fun decOrNull(value: String): Dec? = try { Dec(BigDecimal(value)) } catch (e: NumberFormatException) { null }
actual fun dec(value: Int): Dec = Dec(BigDecimal(value))
actual val DEC_ZERO: Dec = Dec(BigDecimal.ZERO)
