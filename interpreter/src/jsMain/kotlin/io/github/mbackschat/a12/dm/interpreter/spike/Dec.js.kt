package io.github.mbackschat.a12.dm.interpreter.spike

/**
 * JS decimal actual — Double for this POC (exact for the integer/simple-decimal fixtures). Production
 * would bind big.js or use a multiplatform bignum here; the interpreter above never changes.
 */
actual class Dec(val v: Double) : Comparable<Dec> {
    actual operator fun plus(other: Dec): Dec = Dec(v + other.v)
    actual override fun compareTo(other: Dec): Int = v.compareTo(other.v)
}

actual fun dec(value: String): Dec = Dec(value.toDouble())
actual fun dec(value: Int): Dec = Dec(value.toDouble())
actual val DEC_ZERO: Dec = Dec(0.0)
