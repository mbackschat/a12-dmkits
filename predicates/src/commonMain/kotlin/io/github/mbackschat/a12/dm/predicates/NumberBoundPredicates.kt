package io.github.mbackschat.a12.dm.predicates

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.ulp

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
    if (boundsReadAsIntegers(maxFractionalDigits)) {
        roundedHalfAwayFromZero(minValue) > roundedHalfAwayFromZero(maxValue)
    } else {
        minValue > maxValue
    }

/** Whether A12 rounds both bounds to integers before reading them: no fractional digit is allowed. */
private fun boundsReadAsIntegers(maxFractionalDigits: Int?): Boolean = (maxFractionalDigits ?: 0) == 0

// `a - floor(a)` is exact for every double, so the half test reads the stored value rather than a sum.
private fun roundedHalfAwayFromZero(value: Double): Double {
    val magnitude = abs(value)
    val whole = floor(magnitude)
    val rounded = if (magnitude - whole >= 0.5) whole + 1 else whole
    return if (value < 0) -rounded else rounded
}

/** A12's two codeless formal checks on a Number bound's printed form. */
enum class PrintedBoundDefect {
    /** "The value '0' as well as positive values must be provided without a sign." */
    SIGNED_ZERO,

    /** "Only values with up to 15 digits are allowed." */
    TOO_MANY_DIGITS,
}

/**
 * The formal checks a Number declaration's `minValue` or `maxValue` fails once A12 has printed it, empty when
 * it passes both.
 *
 * A12 prints the bound as [numberBoundsInverted] reads it: rounded half away from zero to an integer when
 * [maxFractionalDigits] is 0 or absent, otherwise its shortest decimal in plain form. The printed text must
 * then pass a Number value's own formal checks, measured as two ([KERNEL-FINDINGS KF569],
 * `adapter.laws.CheckerDeclarationAdmissionAgreementTest`): a negative bound that rounds to zero prints as
 * `-0`, which a Number refuses, and the printed form may carry at most 15 digits, counting a leading `0` and
 * not the sign — `0.12345678901234` passes and `0.123456789012345` does not. Either failure withholds
 * `MVK_MIN_VALUE_BIGGER_THAN_MAX_VALUE`, whose rule reads the refused value. A JSON `-0.0` is no signed zero:
 * A12 reads it as `0`, so only a value strictly below zero can be one.
 */
fun printedNumberBoundDefects(value: Double, maxFractionalDigits: Int?): Set<PrintedBoundDefect> {
    val digits = if (boundsReadAsIntegers(maxFractionalDigits)) {
        val rounded = roundedHalfAwayFromZero(value)
        if (value < 0 && rounded == 0.0) return setOf(PrintedBoundDefect.SIGNED_ZERO)
        plainDigitCount(rounded)
    } else {
        plainDigitCount(value)
    }
    return if (digits > MAX_PRINTED_DIGITS) setOf(PrintedBoundDefect.TOO_MANY_DIGITS) else emptySet()
}

/** A12's model-wide digit budget, which a printed bound meets as a value of a Number field. */
private const val MAX_PRINTED_DIGITS = 15

/**
 * The digits of [value]'s shortest decimal written in plain form, a leading `0` before the point included.
 *
 * Read from the platform's own shortest rendering, which both runtimes produce (the JVM's `Double.toString`
 * since JDK 19, and JavaScript's `Number.prototype.toString`), so only the spelling differs: `1.0E15` against
 * `1e+15`. The trailing `.0` the JVM writes on a small integer is not counted, and cannot reach the budget.
 */
private fun plainDigitCount(value: Double): Int {
    if (value == 0.0) return 1
    val text = abs(value).toString()
    val exponentAt = text.indexOfFirst { it == 'e' || it == 'E' }
    val mantissa = if (exponentAt < 0) text else text.substring(0, exponentAt)
    val exponent = if (exponentAt < 0) 0 else text.substring(exponentAt + 1).removePrefix("+").toInt()
    val point = mantissa.indexOf('.').let { if (it < 0) mantissa.length else it }
    val written = mantissa.replace(".", "")
    val leading = written.indexOfFirst { it != '0' }
    val significant = written.substring(leading).trimEnd('0')
    // The position of the decimal point after the first significant digit's place.
    val integerPlaces = point + exponent - leading
    return when {
        integerPlaces <= 0 -> 1 - integerPlaces + significant.length
        integerPlaces >= significant.length -> integerPlaces
        else -> significant.length
    }
}

/** Which of a Number declaration's two bounds is judged. */
enum class NumberBound { MIN_VALUE, MAX_VALUE }

/**
 * Whether a Number declaration's [bound] exceeds the magnitude A12 derives from its declared fractional digits,
 * or null where the answer depends on the JVM A12 runs on.
 *
 * A12 bounds both values by `10^(15 - k) - 10^(-k)`, `k` being [maxFractionalDigits] as declared (an absent count
 * is 0), computed in doubles with `Math.pow`, and refuses a `maxValue` above it and a `minValue` whose ABSOLUTE
 * value is above it ([KERNEL-FINDINGS KF582], source `NumberFieldValueLimitRule`, measured by
 * `adapter.laws.CheckerNumberBoundMagnitudeAgreementTest`). Nothing else enters: not the integer places, not the
 * trait, not whether `k` is within the range its member admits, and not the rounding the inversion law applies,
 * so `999999999999999.4` exceeds the limit at scale 0 though it rounds to it.
 *
 * **The window.** `Math.pow` is specified to return an exact result only when it is representable, and to be
 * within one ulp otherwise; measured on JDK 21.0.12, it differs from the correctly rounded power of ten at 64 of
 * the exponents from -400 to 400. So the limit is decided here only where `10^(15 - k)` is an exact double,
 * `k` from -7 to 15, which covers every count A12's member range admits; beyond it a value within two ulps of
 * the limit is left undecided. The subtracted `10^(-k)` is never exact, but it is fifteen orders of magnitude
 * below the term it is taken from, so its error is far below the limit's own ulp.
 */
fun numberBoundExceedsDigitLimit(bound: NumberBound, value: Double, maxFractionalDigits: Int?): Boolean? {
    val places = maxFractionalDigits ?: 0
    // Java's int arithmetic, which A12's exponents are computed in, wraps; Kotlin's Int wraps the same way.
    val limit = powerOfTen(MAX_PRINTED_DIGITS - places) - powerOfTen(-places)
    val magnitude = when (bound) {
        NumberBound.MIN_VALUE -> abs(value)
        NumberBound.MAX_VALUE -> value
    }
    // An infinite limit is exact, Java's overflow of `Math.pow` being specified, and nothing exceeds it.
    if (limit.isFinite() && MAX_PRINTED_DIGITS - places !in EXACT_POWERS_OF_TEN &&
        abs(magnitude - limit) <= 2 * limit.ulp
    ) {
        return null
    }
    return magnitude > limit
}

/** The exponents whose power of ten a double holds exactly, `10^22` being the largest. */
private val EXACT_POWERS_OF_TEN = 0..22

/** The correctly rounded power of ten: both runtimes parse a decimal literal exactly to the nearest double. */
private fun powerOfTen(exponent: Int): Double = "1e$exponent".toDouble()
