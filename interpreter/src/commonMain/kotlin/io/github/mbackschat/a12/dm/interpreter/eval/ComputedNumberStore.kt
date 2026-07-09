package io.github.mbackschat.a12.dm.interpreter.eval

import io.github.mbackschat.a12.dm.interpreter.Dec

/**
 * A computed NUMBER's stored form (IF92): [rendered] is the string the kernel stores for the cell; [fits] is
 * false when the value's natural scale exceeded the target's `maxFractionalDigits` — the kernel then marks the
 * cell "fehlerhaft berechnet" (ERRORED) regardless of the rendered string's formal validity.
 */
data class StoredComputedNumber(val rendered: String, val fits: Boolean)

/**
 * The kernel's computed-NUMBER store rendering — `CalculationController.handleBerechnetenWert(VkBigDecimal, id)`,
 * shared by BOTH evaluation strategies (the codegen templates call the same controller). Clean-room (IF92):
 *
 *  1. pre-round the computed value to scale 19 HALF_UP (`VkBigDecimal.DEFAULT_SCALE` — observable only for a
 *     quotient/product whose scale exceeds 19);
 *  2. **fit** (natural scale ≤ [maxFractionalDigits]): render at `max(naturalScale, minFractionalDigits)` with
 *     NO length cap — a > 15-digit stored string then trips `stelligkeitZuLang` in the formal check and the
 *     cell is ERRORED with the FULL value stored (never re-rounded to fit);
 *  3. **no-fit**: render length-bounded to 16 significant digits (`BigDecimalUtils.toStringWertLaengenBeschraenkt`
 *     — a value of ≤ 16 significant digits renders canonical, a longer one keeps `16 − integerDigits` fractional
 *     digits, HALF_UP) and mark not-fitting — the caller reports ERRORED unconditionally.
 *
 * The no-fit arm is reachable on a legal model only through a computation declaring
 * `errorCodesToSuppress: [MVK_INVALID_COMPARE_DEC_PLACES]` (the kernel's own escape hatch for the static
 * assignment-scale gate, `IComputation.getErrorCodesToSuppress`); without it the codegen rejects the model.
 */
fun storedComputedNumber(value: Dec, minFractionalDigits: Int, maxFractionalDigits: Int): StoredComputedNumber {
    val v19 = value.scale19()
    val naturalScale = v19.fractionalDigits()
    if (naturalScale <= maxFractionalDigits) {
        return StoredComputedNumber(v19.displayAtScale(maxOf(naturalScale, minFractionalDigits)), true)
    }
    val precision = v19.strippedPrecision()
    val rendered = if (precision <= MAX_STORED_PRECISION) v19.display()
        else v19.displayAtScale(maxOf(0, MAX_STORED_PRECISION - (precision - naturalScale)))
    return StoredComputedNumber(rendered, false)
}

/** The kernel's no-fit length bound — `MAX_FRACTIONAL_DIGITS_TOTAL + 1` in `CalculationController`. */
private const val MAX_STORED_PRECISION = 16
