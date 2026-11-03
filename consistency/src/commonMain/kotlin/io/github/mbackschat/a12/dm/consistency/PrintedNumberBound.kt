package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.predicates.numberBoundAsRead
import kotlin.math.abs

/** A12's two codeless formal checks on a Number bound's printed form. */
internal enum class PrintedBoundDefect {
    /** "The value '0' as well as positive values must be provided without a sign." */
    SIGNED_ZERO,

    /** "Only values with up to 15 digits are allowed." */
    TOO_MANY_DIGITS,
}

/**
 * The formal checks a Number declaration's `minValue` or `maxValue` fails once A12 has printed it, empty when
 * it passes both.
 *
 * A12 prints the bound as it reads it ([numberBoundAsRead]): rounded half away from zero to an integer when
 * [maxFractionalDigits] is 0 or absent, otherwise its shortest decimal in plain form. The printed text must
 * then pass a Number value's own formal checks, measured as two ([KERNEL-FINDINGS KF569],
 * `adapter.laws.CheckerDeclarationAdmissionAgreementTest`): a negative bound that rounds to zero prints as
 * `-0`, which a Number refuses, and the printed form may carry at most 15 digits, counting a leading `0` and
 * not the sign — `0.12345678901234` passes and `0.123456789012345` does not. Either failure withholds
 * `MVK_MIN_VALUE_BIGGER_THAN_MAX_VALUE`, whose rule reads the refused value. A JSON `-0.0` is no signed zero:
 * A12 reads it as `0`, so only a value strictly below zero can be one.
 */
internal fun printedNumberBoundDefects(value: Double, maxFractionalDigits: Int?): Set<PrintedBoundDefect> {
    val printed = numberBoundAsRead(value, maxFractionalDigits)
    // Only rounding can bring a value below zero to zero: a raw negative bound is never 0.0.
    if (value < 0 && printed == 0.0) return setOf(PrintedBoundDefect.SIGNED_ZERO)
    return if (plainDigitCount(printed) > MAX_PRINTED_DIGITS) setOf(PrintedBoundDefect.TOO_MANY_DIGITS) else emptySet()
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
