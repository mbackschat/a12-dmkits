package io.github.mbackschat.a12.dm.interpreter

import java.math.BigDecimal
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
    actual override fun compareTo(other: Dec): Int = v.compareTo(other.v)
    actual fun scale19(): Dec = Dec(v.setScale(19, RoundingMode.HALF_UP))
    actual fun display(): String = v.stripTrailingZeros().toPlainString()
}

actual fun dec(value: String): Dec = Dec(BigDecimal(value))
actual fun decOrNull(value: String): Dec? = try { Dec(BigDecimal(value)) } catch (e: NumberFormatException) { null }
actual fun dec(value: Int): Dec = Dec(BigDecimal(value))
actual val DEC_ZERO: Dec = Dec(BigDecimal.ZERO)
