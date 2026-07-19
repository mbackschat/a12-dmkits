package io.github.mbackschat.a12.dm.interpreter

/**
 * JS decimal actual — backed by **big.js** (proposal §5's named choice), matching the kernel's decimal contract
 * bit-for-bit with the JVM BigDecimal actual: `MathContext(50)` HALF_UP on ×/÷/^ (`VkBigDecimal.INTERNAL_PRECISION`),
 * PLAIN-decimal rendering, and the kernel's reciprocal-first negative power `(1/v)^|n|` (IF153). The interpreter
 * above never changes — the platform difference is confined to this one file.
 *
 * Two big.js facts drive the design:
 *  1. big.js's `DP`/`RM`/`PE`/`NE` are MUTABLE statics on the shared constructor, so a co-resident big.js
 *     consumer could perturb them. We therefore clone an **isolated constructor** ([B]) and configure ONLY it;
 *     the host's `Big` is never read or written.
 *  2. big.js `÷`/negative-`^` round to `DP` DECIMAL places (default 20), and `toString()` switches to exponent
 *     form past `PE`/`NE` (default 21/−7). The kernel needs 50 SIGNIFICANT digits and always-plain rendering, so
 *     we push `PE`/`NE` out (toString is always plain) and cap every product/quotient/power with `prec(50, …)`,
 *     sizing `DP` per division so a small quotient's leading zeros don't eat into its significant digits.
 */
@JsModule("big.js")
@JsNonModule
external class Big(value: String) {
    /** The value's base-10 exponent (public big.js property) — used to size division precision by magnitude. */
    val e: Int
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

/** Our ISOLATED, configured big.js constructor — a fresh clone from [bigDefault] so nothing outside this file
 *  can perturb its context, and vice versa. `PE`/`NE` are pushed out so `toString()` is ALWAYS plain decimal.
 *  `DP` is (re)set per division in [divTo50]; its initial value is irrelevant (no other op reads it). */
private val B: dynamic = run {
    val ctor: dynamic = bigDefault()
    ctor.DP = 51
    ctor.RM = HALF_UP
    ctor.PE = 1_000_000
    ctor.NE = -1_000_000
    ctor
}

/** A big.js instance from the isolated constructor — called as a function (no `new`), which big.js supports.
 *  The constructor is bound to a local first so Kotlin/JS emits a value invoke, not a getter call. */
private fun big(value: String): Big {
    val ctor: dynamic = B
    return ctor(value).unsafeCast<Big>()
}

/**
 * `a ÷ b` at the kernel's `MathContext(50)` — 50 SIGNIFICANT digits HALF_UP. big.js `div` rounds to `DP`
 * DECIMAL places, so a small quotient (leading zeros, which are not significant) would keep too few significant
 * digits. Size `DP` by the operands' magnitude gap (`.e` is the base-10 exponent) BEFORE dividing, then round to
 * 50 significant digits. `B` is our own constructor and Kotlin/JS is single-threaded, so setting `B.DP` per call
 * is safe and local.
 */
private fun divTo50(a: Big, b: Big): Big {
    val gap = b.e - a.e                            // ≈ leading-zero count of a/b when > 0
    B.DP = 51 + if (gap > 0) gap else 0           // 50 significant digits + a HALF_UP guard, plus leading zeros
    return a.div(b).prec(50, HALF_UP)
}

actual class Dec(val v: Big) : Comparable<Dec> {
    actual operator fun plus(other: Dec): Dec = Dec(v.plus(other.v))       // + / − are exact (the kernel never caps them)
    actual operator fun minus(other: Dec): Dec = Dec(v.minus(other.v))
    actual operator fun times(other: Dec): Dec = Dec(v.times(other.v).prec(50, HALF_UP))
    actual fun div(other: Dec): Dec = Dec(divTo50(v, other.v))

    actual fun pow(exponent: Int): Dec =
        if (exponent >= 0) Dec(v.pow(exponent).prec(50, HALF_UP))
        // Negative power is the kernel's RECIPROCAL-FIRST form (1/v)^|n|, each step capped at 50 sig HALF_UP —
        // matching `VkBigDecimal.power` → `getKehrwert(this).power(|n|)` and the JVM actual (IF153).
        else Dec(divTo50(big("1"), v).pow(-exponent).prec(50, HALF_UP))

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

    actual fun display(): String = v.toString()   // toString is now always plain (PE/NE pushed out)

    actual fun display(minFractionalDigits: Int): String {
        // The kernel's computed-store fit-arm rule (`handleBerechnetenWert`): max(naturalScale, minFractionalDigits),
        // UNCAPPED — a > 15-digit stored form renders in full; the formal check flags it, the kernel never
        // re-rounds to fit (IF92, matching the JVM actual). toString() is plain, so the natural scale reads off it;
        // toFixed pads with zeros to the target width (and, for a whole value, naturalScale 0 ≤ min ⇒ min wins,
        // exactly like the JVM's maxOf(negative-scale, min)).
        val s = v.toString()
        val dot = s.indexOf('.')
        val naturalScale = if (dot < 0) 0 else s.length - dot - 1
        return v.toFixed(maxOf(naturalScale, minFractionalDigits))
    }

    /** The stripped (precision, scale) pair, mirroring `BigDecimal.stripTrailingZeros()` exactly — derived from
     *  the canonical toString() (trailing FRACTION zeros already stripped by big.js, and now always plain): a
     *  fractional value's scale is its fraction length and its precision the significant-digit count (leading
     *  zeros excluded); a WHOLE value's trailing zeros shift the scale NEGATIVE (`100` → precision 1, scale −2),
     *  exactly like BigDecimal. Zero is (1, 0). */
    private fun strippedParts(): Pair<Int, Int> {
        val s = v.toString().removePrefix("-")
        val dot = s.indexOf('.')
        if (dot >= 0) {
            val digits = s.replace(".", "").trimStart('0')
            return (if (digits.isEmpty()) 1 else digits.length) to (s.length - dot - 1)
        }
        val noLead = s.trimStart('0')
        if (noLead.isEmpty()) return 1 to 0                       // zero
        val trailing = noLead.length - noLead.trimEnd('0').length
        return (noLead.length - trailing) to -trailing
    }

    actual fun fractionalDigits(): Int = strippedParts().second

    actual fun strippedPrecision(): Int = strippedParts().first

    // toFixed rounds HALF_UP (our isolated RM) and zero-pads to exactly [scale] — the setScale(scale, HALF_UP).toPlainString() twin.
    actual fun displayAtScale(scale: Int): String = v.toFixed(scale)

    // VALUE equality, scale-INSENSITIVE — consistent with [compareTo] (big.js `cmp`), matching the JVM actual
    // (IF41). big.js `toString()` is the canonical trailing-zero-stripped form, so it keys a hashCode that is
    // consistent with equals (equal values hash the same).
    override fun equals(other: Any?): Boolean = other is Dec && v.cmp(other.v) == 0
    override fun hashCode(): Int = v.toString().hashCode()
}

actual fun dec(value: String): Dec = Dec(big(value))
actual fun decOrNull(value: String): Dec? = try { Dec(big(value)) } catch (e: Throwable) { null }   // big.js throws on a non-numeric string
actual fun dec(value: Int): Dec = Dec(big(value.toString()))
actual val DEC_ZERO: Dec = Dec(big("0"))
