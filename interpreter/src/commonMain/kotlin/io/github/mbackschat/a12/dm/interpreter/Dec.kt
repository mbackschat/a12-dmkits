package io.github.mbackschat.a12.dm.interpreter

/**
 * The rounding direction for [Dec.roundTo] — the kernel's three round functions. `RoundDown` rounds toward
 * −∞ (FLOOR — `RoundDown(-1.4, 0) = -2`), `RoundUp` toward +∞ (CEILING — `RoundUp(-1.4, 0) = -1`),
 * `RoundAccounting` HALF_UP / away-from-zero on the `.5` boundary (`RoundAccounting(-2.5, 0) = -3`). Mapped
 * from `BigDecimalUtils.{abrunden,aufrunden,runden}` in the kernel runtime (`RoundingMode.{FLOOR,CEILING,HALF_UP}`).
 */
enum class RoundMode { FLOOR, CEILING, HALF_UP }

/**
 * The interpreter's decimal — the ONE platform-specific primitive (proposal §5's "thin shim"). All the
 * rest of the engine is common code. The JVM actual wraps java.math.BigDecimal and the JS actual wraps
 * **big.js**, so both carry the same arbitrary-precision arithmetic (`0.1 + 0.2 == 0.3` exactly on each).
 * Any swap is localized to exactly the two actual files.
 */
expect class Dec : Comparable<Dec> {
    operator fun plus(other: Dec): Dec
    operator fun minus(other: Dec): Dec
    operator fun times(other: Dec): Dec

    /** This ÷ [other], capped at the kernel's `MathContext(50)` HALF_UP (`VkBigDecimal.INTERNAL_PRECISION`) — a
     *  non-terminating quotient is rounded to 50 significant digits. The evaluator classifies zero first as a
     *  domain failure, which comparisons project to UNKNOWN and numeric computations retain as target invalidity. */
    fun div(other: Dec): Dec

    /** This raised to a whole-number [exponent], at `MathContext(50)` HALF_UP. The caller guards the kernel's quiet
     *  cases (0 to a negative power, |exponent| > 1000 → Unknown) and that the exponent is whole. */
    fun pow(exponent: Int): Dec
    override fun compareTo(other: Dec): Int

    /**
     * This value rounded to at most **scale 19** `HALF_UP` — the kernel's `VkBigDecimal.DEFAULT_SCALE` pre-rounding that
     * `BedingungsOperatorHelper.vergleiche` applies to BOTH operands before a NUMBER comparison (`<`/`>`/`==`/…).
     * Observable only beyond 19 fractional digits (e.g. a scale-10 × scale-10 product → scale 20); for ≤19
     * fractional digits it only pads zeros, a no-op for every value reading, so an actual may return the value as
     * is there. NOT applied to `min`/`max`
     * selection — the kernel does those at full precision (`VkBigDecimal.compareTo`).
     */
    fun scale19(): Dec

    /**
     * This compared to [other] under the kernel's scale-19 NUMBER comparison rounding — **bit-identical** to
     * `scale19().compareTo(other.scale19())`, but allocates the rounded operands ONLY when one exceeds 19
     * fractional digits. For ≤19 fractional digits the scale-19 rounding only pads trailing zeros, which
     * [compareTo] ignores, so the rounding is a no-op for the *outcome* and is skipped (the hot path of every
     * NUMBER `<`/`>`/`==`/`!=`, where it saved two `BigDecimal` allocations per comparison).
     */
    fun compareScaled19(other: Dec): Int

    /** Absolute value (`|this|`) — for the `Abs`/`AbsValue` operators. */
    fun abs(): Dec

    /**
     * This value rounded to [places] decimal places with [mode] — the kernel's round functions
     * (`RoundDown`/`RoundUp`/`RoundAccounting` and their `*Value` field siblings, which round to [places] 0).
     * Reproduces `BigDecimalUtils.runden`'s **two step**: first setScale(19, HALF_UP) — the
     * `VkBigDecimal.DEFAULT_SCALE` pre-round that avoids artefacts like `RoundDown(1/3 + 2/3, 0) = 0` — then
     * setScale([places], [mode]).
     */
    fun roundTo(places: Int, mode: RoundMode): Dec

    /** Canonical numeric rendering, trailing zeros stripped (`49.90` → `49.9`, `0` → `0`) — for computed values. */
    fun display(): String

    /**
     * STORED-form rendering of a computed value: padded to at least [minFractionalDigits] fractional digits —
     * the kernel's computed-number store rule (`CalculationController.handleBerechnetenWert`, shared by BOTH
     * evaluation strategies; IF82/IF92). The digit count is `max(stripTrailingZeros().scale(),
     * minFractionalDigits)` — natural significant fractional digits are kept, with **NO length cap**: a value
     * whose stored form exceeds 15 total digits renders in FULL (and then trips `stelligkeitZuLang` in the
     * formal check — the kernel stores the full string and marks the cell errored, it never re-rounds to fit).
     * With [minFractionalDigits] `0` this equals [display] (canonical).
     */
    fun display(minFractionalDigits: Int): String

    /**
     * The number of significant FRACTIONAL digits — the kernel's `stripTrailingZeros().scale()` (`minScale` in
     * `handleBerechnetenWert`): `10.50` → 1, `2.4723` → 4, `10` → 0, `100` → **−2** (a whole value's stripped
     * scale is negative, exactly like `BigDecimal`), `0` → 0. Drives the computed-store fit test (IF92).
     */
    fun fractionalDigits(): Int

    /**
     * The number of significant digits of the trailing-zero-STRIPPED value — the kernel's
     * `stripTrailingZeros().precision()`: `2.4723` → 5, `100` → 1, `0.001` → 1, `0` → 1. Drives the computed-store
     * length-bounded arm (IF92, `BigDecimalUtils.toStringWertLaengenBeschraenkt`).
     */
    fun strippedPrecision(): Int

    /**
     * This value rendered at EXACTLY [scale] fractional digits (≥ 0), rounding HALF_UP when [scale] drops
     * significant digits and zero-padding when it exceeds them — `setScale(scale, HALF_UP).toPlainString()`.
     * The computed-store primitive (IF92): the fit arm calls it at a scale ≥ the natural scale (exact), the
     * length-bounded arm at the 16-significant-digit bound (rounds).
     */
    fun displayAtScale(scale: Int): String
}

expect fun dec(value: String): Dec

/**
 * Parse [value] to a [Dec], or **null** when it is not a well-formed number — so a malformed value resolves to
 * `Val.Unknown` instead of throwing. The direct-operand path guards malformed fields up front (the rule is
 * suppressed), but a malformed value reached through an aggregate (`Sum(x*)`) or a computation is parsed
 * without that guard, so the parse itself must never crash. (Formal-error *detection* stays `FormalError`'s job.)
 */
expect fun decOrNull(value: String): Dec?

expect fun dec(value: Int): Dec
expect val DEC_ZERO: Dec
