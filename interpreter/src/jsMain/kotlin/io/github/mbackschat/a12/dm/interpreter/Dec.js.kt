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
    fun abs(): Big
    fun round(dp: Int, rm: Int): Big   // dp = decimal places; rm = rounding mode (1 = roundHalfUp)
    fun toFixed(dp: Int): String       // plain notation with EXACTLY dp decimal places (pads / rounds)
    override fun toString(): String   // big.js renders trailing-zero-stripped plain notation
}

actual class Dec(val v: Big) : Comparable<Dec> {
    actual operator fun plus(other: Dec): Dec = Dec(v.plus(other.v))
    actual operator fun minus(other: Dec): Dec = Dec(v.minus(other.v))
    // big.js `×` is exact — it matches the kernel's `MathContext(50)` cap up to 50 significant digits; `÷`/`^`
    // match up to big.js's Big.DP. The JVM is the differential-exact target (the kernel is JVM-only), so JS
    // keeping more precision than the 50-sig-digit / Big.DP cap is a documented JS edge.
    actual operator fun times(other: Dec): Dec = Dec(v.times(other.v))
    actual fun div(other: Dec): Dec = Dec(v.div(other.v))
    actual fun pow(exponent: Int): Dec = Dec(v.pow(exponent))
    actual override fun compareTo(other: Dec): Int = v.cmp(other.v)
    actual fun abs(): Dec = Dec(v.abs())
    actual fun scale19(): Dec = Dec(v.round(19, 1))   // 1 = big.js roundHalfUp ≡ JVM RoundingMode.HALF_UP
    // big.js exposes no cheap fractional-scale accessor, and JS is not the differential target (the kernel is
    // JVM-only) — so JS keeps the exact, explicit round-both form. The allocation-skipping fast path is the
    // JVM actual's; here correctness is all that matters and round(19,1).cmp() == scale19().compareTo().
    actual fun compareScaled19(other: Dec): Int = v.round(19, 1).cmp(other.v.round(19, 1))
    actual fun roundTo(places: Int, mode: RoundMode): Dec {
        // The kernel's two-step BigDecimalUtils.runden: pre-round to DEFAULT_SCALE (19) HALF_UP, then to places.
        // big.js has only roundDown(0, toward zero), roundHalfUp(1), roundUp(3, away from zero) — it has NO
        // FLOOR/CEILING, so emulate them by sign: FLOOR = away-from-zero for negatives else toward-zero (and
        // CEILING the mirror), which is exactly toward −∞ / +∞.
        val pre = v.round(19, 1)
        val negative = pre.cmp(Big("0")) < 0
        val rm = when (mode) {
            RoundMode.HALF_UP -> 1
            RoundMode.FLOOR -> if (negative) 3 else 0
            RoundMode.CEILING -> if (negative) 0 else 3
        }
        return Dec(pre.round(places, rm))
    }
    actual fun display(): String = v.toString()

    actual fun display(minFractionalDigits: Int): String {
        // The kernel's computed-store fit-arm rule (`handleBerechnetenWert`): max(naturalScale, minFractionalDigits),
        // UNCAPPED — a > 15-digit stored form renders in full; the formal check flags it, the kernel never
        // re-rounds to fit (IF92, matching the JVM actual). big.js has no scale accessor, so read the natural
        // scale off the canonical toString() (trailing zeros stripped, plain notation for in-range values).
        // toFixed pads with zeros to the target width.
        val s = v.toString()
        val dot = s.indexOf('.')
        val naturalScale = if (dot < 0) 0 else s.length - dot - 1
        return v.toFixed(maxOf(naturalScale, minFractionalDigits))
    }

    /** The stripped (precision, scale) pair, mirroring `BigDecimal.stripTrailingZeros()` exactly — derived from
     *  the canonical toString() (trailing FRACTION zeros already stripped by big.js): a fractional value's scale
     *  is its fraction length and its precision the significant-digit count (leading zeros excluded); a WHOLE
     *  value's trailing zeros shift the scale NEGATIVE (`100` → precision 1, scale −2), exactly like BigDecimal.
     *  Zero is (1, 0). Plain notation assumed (the file's standing toString edge for extreme exponents). */
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

    // toFixed rounds with big.js's global Big.RM (the default 1 = roundHalfUp, which the interpreter never
    // changes) and zero-pads to exactly [scale] — the setScale(scale, HALF_UP).toPlainString() twin.
    actual fun displayAtScale(scale: Int): String = v.toFixed(scale)

    // VALUE equality, scale-INSENSITIVE — consistent with [compareTo] (big.js `cmp`), matching the JVM actual
    // (IF41). big.js `toString()` is the canonical trailing-zero-stripped form, so it keys a hashCode that is
    // consistent with equals (equal values hash the same).
    override fun equals(other: Any?): Boolean = other is Dec && v.cmp(other.v) == 0
    override fun hashCode(): Int = v.toString().hashCode()
}

actual fun dec(value: String): Dec = Dec(Big(value))
actual fun decOrNull(value: String): Dec? = try { Dec(Big(value)) } catch (e: Throwable) { null }   // big.js throws on a non-numeric string
actual fun dec(value: Int): Dec = Dec(Big(value.toString()))
actual val DEC_ZERO: Dec = Dec(Big("0"))
