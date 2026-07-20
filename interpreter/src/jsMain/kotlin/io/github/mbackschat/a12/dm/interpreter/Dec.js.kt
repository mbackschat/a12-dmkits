package io.github.mbackschat.a12.dm.interpreter

/**
 * JS decimal actual — backed by **big.js** (proposal §5's named choice), matching the kernel's decimal contract
 * bit-for-bit with the JVM BigDecimal actual: `MathContext(50)` HALF_UP on +/−/×/÷/^ (`VkBigDecimal.INTERNAL_PRECISION`),
 * PLAIN-decimal rendering, and the kernel's reciprocal-first negative power `(1/v)^|n|`. The interpreter
 * above never changes — the platform difference is confined to this one file.
 *
 * Two big.js facts drive the design:
 *  1. big.js's `DP`/`RM`/`PE`/`NE` are MUTABLE statics on the shared constructor, so a co-resident big.js
 *     consumer could perturb them. We therefore clone an **isolated constructor** ([B]) and configure ONLY it;
 *     the host's `Big` is never read or written.
 *  2. big.js division rounds by decimal places and `toString()` switches notation at finite exponent limits.
 *     Neither policy is the kernel contract. Division therefore operates on normalized mantissas with a fixed
 *     truncation budget before the one significant-digit HALF_UP rounding, while rendering reads the public
 *     coefficient/exponent representation directly. Magnitude never expands the division budget or controls notation.
 */
@JsModule("big.js")
@JsNonModule
external class Big(value: String) {
    /** Significant base-10 digits (big.js public property; trailing zeros are normalized away). */
    val c: Array<Int>
    /** The value's base-10 exponent (public big.js property) — used to size division precision by magnitude. */
    val e: Int
    /** Sign (big.js public property): 1 or -1. */
    val s: Int
    fun plus(other: Big): Big
    fun minus(other: Big): Big
    fun times(other: Big): Big
    fun div(other: Big): Big               // rounds to the constructor's DP decimal places
    fun pow(exp: Int): Big                 // integer exponent; negative uses DP
    fun prec(sd: Int, rm: Int): Big        // round to sd SIGNIFICANT digits (the MathContext(50) primitive)
    fun cmp(other: Big): Int
    fun abs(): Big
    fun round(dp: Int, rm: Int): Big       // dp = decimal places; rm = rounding mode (1 = roundHalfUp)
    fun toFixed(dp: Int): String           // plain notation with EXACTLY dp decimal places (pads / rounds)
    override fun toString(): String        // trailing-zero-stripped plain notation (PE/NE pushed out below)
}

/** The big.js module default (the `Big` constructor) imported as a no-arg factory — calling it with no argument
 *  is big.js's documented clone: it returns a FRESH, independent constructor with its own DP/RM/PE/NE. */
@JsModule("big.js")
@JsNonModule
private external fun bigDefault(): dynamic

/** big.js roundHalfUp — the kernel's `RoundingMode.HALF_UP`. */
private const val HALF_UP = 1

/** Our isolated arithmetic constructor — no co-resident big.js consumer can mutate its context. */
private val B: dynamic = run {
    val ctor: dynamic = bigDefault()
    ctor.DP = 60
    ctor.RM = HALF_UP
    ctor
}

/** A separate isolated division constructor. Its fixed 60-place truncation operates only on normalized
 * mantissas in [0.1, 10), so it always exposes more than the 51 significant digits needed for one correct
 * HALF_UP rounding and never grows with the result magnitude. */
private val DIV_B: dynamic = run {
    val ctor: dynamic = bigDefault()
    ctor.DP = 60
    ctor.RM = 0
    ctor
}

/** A big.js instance from the isolated constructor — called as a function (no `new`), which big.js supports.
 *  The constructor is bound to a local first so Kotlin/JS emits a value invoke, not a getter call. */
private fun bigWith(ctor: dynamic, value: String): Big {
    return ctor(value).unsafeCast<Big>()
}

private fun big(value: String): Big = bigWith(B, value)

private fun coefficient(value: Big): String = value.c.joinToString("")

/** Scientific source using [exponent] without relying on big.js's finite PE/NE rendering switches. */
private fun scientific(value: Big, exponent: Int = value.e): String {
    val digits = coefficient(value)
    val sign = if (value.s < 0 && digits != "0") "-" else ""
    val fraction = if (digits.length == 1) "" else "." + digits.substring(1)
    return "$sign${digits[0]}${fraction}e$exponent"
}

/** Unconditional plain rendering from big.js's normalized coefficient/exponent representation. */
private fun plain(value: Big): String {
    val digits = coefficient(value)
    if (digits == "0") return "0"
    val unsigned = when {
        value.e < 0 -> "0." + "0".repeat(-value.e - 1) + digits
        value.e + 1 >= digits.length -> digits + "0".repeat(value.e + 1 - digits.length)
        else -> digits.substring(0, value.e + 1) + "." + digits.substring(value.e + 1)
    }
    return if (value.s < 0) "-$unsigned" else unsigned
}

/**
 * `a ÷ b` at the kernel's `MathContext(50)`: normalize both operands to one-digit scientific mantissas, truncate
 * their quotient beyond the guard region, perform the single HALF_UP significant-digit rounding, then restore
 * the exact base-10 exponent by construction. This avoids both double rounding and big.js's one-million-DP limit.
 */
private fun divTo50(a: Big, b: Big): Big {
    val divisor: dynamic = DIV_B
    val normalizedA = bigWith(divisor, scientific(a, 0))
    val normalizedB = bigWith(divisor, scientific(b, 0))
    val rounded = normalizedA.div(normalizedB).prec(50, HALF_UP)
    return big(scientific(rounded, rounded.e + a.e - b.e))
}

private var powerPeakDigits = 0

internal fun resetDecimalPowerPeakDigits() {
    powerPeakDigits = 0
}

internal fun decimalPowerPeakDigits(): Int = powerPeakDigits

private fun observePower(value: Big): Big {
    powerPeakDigits = maxOf(powerPeakDigits, value.c.size)
    return value
}

/** Bounded exponentiation under the same guard-precision rule as `BigDecimal.pow(MathContext)`: the requested
 * precision plus the exponent digit count and one carry guard, followed by one 50-digit result rounding. */
private fun powPositive(base: Big, exponent: Int): Big {
    if (exponent == 0) return big("1")
    val workPrecision = 51 + exponent.toString().length
    val factor = observePower(base.prec(workPrecision, HALF_UP))
    var result = big("1")
    var bit = 1
    while (bit <= exponent / 2) bit = bit shl 1
    while (bit > 0) {
        result = observePower(result.times(result).prec(workPrecision, HALF_UP))
        if (exponent and bit != 0) {
            result = observePower(result.times(factor).prec(workPrecision, HALF_UP))
        }
        bit = bit ushr 1
    }
    return result.prec(50, HALF_UP)
}

actual class Dec(val v: Big) : Comparable<Dec> {
    actual operator fun plus(other: Dec): Dec = Dec(v.plus(other.v).prec(50, HALF_UP))
    actual operator fun minus(other: Dec): Dec = Dec(v.minus(other.v).prec(50, HALF_UP))
    actual operator fun times(other: Dec): Dec = Dec(v.times(other.v).prec(50, HALF_UP))
    actual fun div(other: Dec): Dec = Dec(divTo50(v, other.v))

    actual fun pow(exponent: Int): Dec =
        if (exponent >= 0) Dec(powPositive(v, exponent))
        // Negative power is the kernel's RECIPROCAL-FIRST form (1/v)^|n|, each step capped at 50 sig HALF_UP —
        // matching the JVM actual and both real-kernel strategies.
        else Dec(powPositive(divTo50(big("1"), v), -exponent))

    actual override fun compareTo(other: Dec): Int = v.cmp(other.v)
    actual fun abs(): Dec = Dec(v.abs())
    actual fun scale19(): Dec = Dec(v.round(19, HALF_UP))   // round(dp, rm) is explicit — no global-RM reliance

    // big.js exposes no cheap fractional-scale accessor, and the allocation-skipping fast path is the JVM
    // actual's — here correctness is all that matters and round(19,…).cmp() == scale19().compareTo().
    actual fun compareScaled19(other: Dec): Int = v.round(19, HALF_UP).cmp(other.v.round(19, HALF_UP))

    actual fun roundTo(places: Int, mode: RoundMode): Dec {
        // The kernel's two-step BigDecimalUtils.runden: pre-round to DEFAULT_SCALE (19) HALF_UP, then to places.
        // big.js has only roundDown(0, toward zero), roundHalfUp(1), roundUp(3, away from zero) — no FLOOR/CEILING,
        // so emulate them by sign: FLOOR = away-from-zero for negatives else toward-zero (CEILING mirrors it),
        // which is exactly toward −∞ / +∞.
        val pre = v.round(19, HALF_UP)
        val negative = pre.cmp(big("0")) < 0
        val rm = when (mode) {
            RoundMode.HALF_UP -> HALF_UP
            RoundMode.FLOOR -> if (negative) 3 else 0
            RoundMode.CEILING -> if (negative) 0 else 3
        }
        return Dec(pre.round(places, rm))
    }

    actual fun display(): String = plain(v)

    actual fun display(minFractionalDigits: Int): String {
        // The kernel's computed-store fit-arm rule (`handleBerechnetenWert`): max(naturalScale, minFractionalDigits),
        // UNCAPPED — a > 15-digit stored form renders in full; the formal check flags it, the kernel never
        // re-rounds to fit (IF92, matching the JVM actual). toString() is plain, so the natural scale reads off it;
        // toFixed pads with zeros to the target width (and, for a whole value, naturalScale 0 ≤ min ⇒ min wins,
        // exactly like the JVM's maxOf(negative-scale, min)).
        val s = plain(v)
        val dot = s.indexOf('.')
        val naturalScale = if (dot < 0) 0 else s.length - dot - 1
        return v.toFixed(maxOf(naturalScale, minFractionalDigits))
    }

    /** The stripped (precision, scale) pair, mirroring `BigDecimal.stripTrailingZeros()` exactly. big.js already
     * normalizes its public coefficient by removing insignificant trailing zeros. */
    private fun strippedParts(): Pair<Int, Int> {
        if (v.c.size == 1 && v.c[0] == 0) return 1 to 0
        val precision = v.c.size
        return precision to (precision - v.e - 1)
    }

    actual fun fractionalDigits(): Int = strippedParts().second

    actual fun strippedPrecision(): Int = strippedParts().first

    // toFixed rounds HALF_UP (our isolated RM) and zero-pads to exactly [scale] — the setScale(scale, HALF_UP).toPlainString() twin.
    actual fun displayAtScale(scale: Int): String = v.toFixed(scale)

    // VALUE equality, scale-INSENSITIVE — consistent with [compareTo] (big.js `cmp`), matching the JVM actual.
    override fun equals(other: Any?): Boolean = other is Dec && v.cmp(other.v) == 0
    override fun hashCode(): Int = scientific(v).hashCode()
}

actual fun dec(value: String): Dec = Dec(big(value))
actual fun decOrNull(value: String): Dec? = try { Dec(big(value)) } catch (e: Throwable) { null }   // big.js throws on a non-numeric string
actual fun dec(value: Int): Dec = Dec(big(value.toString()))
actual val DEC_ZERO: Dec = Dec(big("0"))
