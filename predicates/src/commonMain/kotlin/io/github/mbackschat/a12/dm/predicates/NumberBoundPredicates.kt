package io.github.mbackschat.a12.dm.predicates

import kotlin.math.abs
import kotlin.math.floor

/**
 * Whether a Number declaration's bounds draw A12's `MVK_MIN_VALUE_BIGGER_THAN_MAX_VALUE`.
 *
 * **The bounds are not compared as written when no fractional digit is allowed** ([KERNEL-FINDINGS KF569],
 * `adapter.laws.CheckerDeclarationAdmissionAgreementTest`). With [maxFractionalDigits] 0 or absent, both are
 * first rounded to integers HALF AWAY FROM ZERO on their exact double value, so `1.4` over `1` and `-1.5` over
 * `-1.6` are admitted while `1.5` over `1.4` and `2.5` over `2` are refused. With one fractional digit or more,
 * and with the explicit `-1` that suppresses the count, the raw values are compared: `1.04` over `1` is refused
 * at one digit, so the bounds are not rounded to the declared scale either.
 *
 * The three rival roundings each get a measured row wrong: `Math.round`, which rounds half UP, refuses `-1.5`
 * over `-1.6`; `floor(x + 0.5)` refuses `0.49999999999999994` over `0`, because the addition rounds to 1; and
 * half-to-even admits `2.5` over `2`. Equal bounds are admitted.
 *
 * Two consumers state this law: the portable checker, which reports it, and modelkit's `NumberSpec`, which
 * refuses it while authoring.
 */
fun numberBoundsInverted(minValue: Double, maxValue: Double, maxFractionalDigits: Int?): Boolean =
    numberBoundAsRead(minValue, maxFractionalDigits) > numberBoundAsRead(maxValue, maxFractionalDigits)

/**
 * A Number bound as A12 reads it: rounded half away from zero to an integer when no fractional digit is allowed,
 * otherwise as written. [numberBoundsInverted] compares these, and the portable checker prints them.
 */
fun numberBoundAsRead(value: Double, maxFractionalDigits: Int?): Double =
    if ((maxFractionalDigits ?: 0) == 0) roundedHalfAwayFromZero(value) else value

// `a - floor(a)` is exact for every double, so the half test reads the stored value rather than a sum.
private fun roundedHalfAwayFromZero(value: Double): Double {
    val magnitude = abs(value)
    val whole = floor(magnitude)
    val rounded = if (magnitude - whole >= 0.5) whole + 1 else whole
    return if (value < 0) -rounded else rounded
}
