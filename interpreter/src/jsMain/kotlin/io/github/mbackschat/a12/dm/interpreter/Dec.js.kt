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
    fun minus(other: Big): Big
    fun times(other: Big): Big
    fun div(other: Big): Big       // rounds to Big.DP decimal places (default 20)
    fun pow(exp: Int): Big         // integer exponent; negative uses Big.DP
    fun cmp(other: Big): Int
    fun round(dp: Int, rm: Int): Big   // dp = decimal places; rm = rounding mode (1 = roundHalfUp)
    override fun toString(): String   // big.js renders trailing-zero-stripped plain notation
}

actual class Dec(val v: Big) : Comparable<Dec> {
    actual operator fun plus(other: Dec): Dec = Dec(v.plus(other.v))
    actual operator fun minus(other: Dec): Dec = Dec(v.minus(other.v))
    actual operator fun times(other: Dec): Dec = Dec(v.times(other.v))
    // ÷ and ^ match the JVM (kernel MathContext(50)) up to big.js's Big.DP; the JVM is the differential-exact
    // target (the kernel is JVM-only) — JS high-precision division beyond Big.DP is a documented JS edge.
    actual fun div(other: Dec): Dec = Dec(v.div(other.v))
    actual fun pow(exponent: Int): Dec = Dec(v.pow(exponent))
    actual override fun compareTo(other: Dec): Int = v.cmp(other.v)
    actual fun scale19(): Dec = Dec(v.round(19, 1))   // 1 = big.js roundHalfUp ≡ JVM RoundingMode.HALF_UP
    actual fun display(): String = v.toString()
}

actual fun dec(value: String): Dec = Dec(Big(value))
actual fun decOrNull(value: String): Dec? = try { Dec(Big(value)) } catch (e: Throwable) { null }   // big.js throws on a non-numeric string
actual fun dec(value: Int): Dec = Dec(Big(value.toString()))
actual val DEC_ZERO: Dec = Dec(Big("0"))
