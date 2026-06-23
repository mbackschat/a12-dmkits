package io.github.mbackschat.a12.dm.interpreter

import java.math.BigDecimal

/**
 * JVM decimal actual — full-precision BigDecimal. NOTE: this is NOT yet bit-for-bit the kernel's arithmetic:
 * the kernel caps `×`/`÷`/`^` at `MathContext(50)` (HALF_UP) and rounds both operands to scale 19 (HALF_UP)
 * before comparing (`VkBigDecimal.DEFAULT_SCALE`/`INTERNAL_PRECISION`). We match on every practical input and
 * diverge only beyond those precisions — the documented open edge (INTERPRETER-BUILDOUT.md, "Audit findings still OPEN").
 */
actual class Dec(val v: BigDecimal) : Comparable<Dec> {
    actual operator fun plus(other: Dec): Dec = Dec(v + other.v)
    actual operator fun minus(other: Dec): Dec = Dec(v - other.v)
    actual operator fun times(other: Dec): Dec = Dec(v * other.v)
    actual override fun compareTo(other: Dec): Int = v.compareTo(other.v)
    actual fun display(): String = v.stripTrailingZeros().toPlainString()
}

actual fun dec(value: String): Dec = Dec(BigDecimal(value))
actual fun dec(value: Int): Dec = Dec(BigDecimal(value))
actual val DEC_ZERO: Dec = Dec(BigDecimal.ZERO)
