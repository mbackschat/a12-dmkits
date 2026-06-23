package io.github.mbackschat.a12.dm.interpreter

/**
 * JS decimal actual — backed by **big.js** (proposal §5's named choice), so JS gets the SAME
 * arbitrary-precision decimal arithmetic as the JVM's BigDecimal. The interpreter above never changes:
 * the platform difference is confined to this one file.
 */
@JsModule("big.js")
@JsNonModule
external class Big(value: String) {
    fun plus(other: Big): Big
    fun cmp(other: Big): Int
    override fun toString(): String   // big.js renders trailing-zero-stripped plain notation
}

actual class Dec(val v: Big) : Comparable<Dec> {
    actual operator fun plus(other: Dec): Dec = Dec(v.plus(other.v))
    actual override fun compareTo(other: Dec): Int = v.cmp(other.v)
    actual fun display(): String = v.toString()
}

actual fun dec(value: String): Dec = Dec(Big(value))
actual fun dec(value: Int): Dec = Dec(Big(value.toString()))
actual val DEC_ZERO: Dec = Dec(Big("0"))
