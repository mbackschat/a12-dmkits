package io.github.mbackschat.a12.dm.interpreter

import java.math.BigDecimal

/** JVM decimal actual — full-precision BigDecimal, the same arithmetic the kernel uses. */
actual class Dec(val v: BigDecimal) : Comparable<Dec> {
    actual operator fun plus(other: Dec): Dec = Dec(v + other.v)
    actual override fun compareTo(other: Dec): Int = v.compareTo(other.v)
}

actual fun dec(value: String): Dec = Dec(BigDecimal(value))
actual fun dec(value: Int): Dec = Dec(BigDecimal(value))
actual val DEC_ZERO: Dec = Dec(BigDecimal.ZERO)
