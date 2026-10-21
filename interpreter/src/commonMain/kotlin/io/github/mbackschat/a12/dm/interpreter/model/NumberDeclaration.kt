package io.github.mbackschat.a12.dm.interpreter.model

import io.github.mbackschat.a12.dm.interpreter.asciiLongOrNull
import io.github.mbackschat.a12.dm.interpreter.javaTrimmed
import io.github.mbackschat.a12.dm.interpreter.A12DocumentJson
import io.github.mbackschat.a12.dm.interpreter.dec

/**
 * A Number declaration's fractional pair as the runtime reads it: [minimum] and [maximum] fractional digits, and
 * whether a stored value's fractional part is checked at all ([checked]).
 *
 * A12 writes "no bound" as `-1` and resolves it before validating (KERNEL-FINDINGS KF564, measured 2026-10-13 by
 * a12-kernel-lean on both strategies and re-measured here by `NumberDeclarationIngressDiffTest`): a `-1` minimum
 * beside a positive maximum reads as `0`, and a `-1` maximum fixes the count at the minimum, an absent minimum
 * reading as `0`. The exception is a `-1` minimum beside a zero maximum: neither the separator test nor the
 * fractional-count test is armed, so `1.555` is valid there, and a computation fits to zero places.
 */
internal data class FractionalPair(val minimum: Int?, val maximum: Int, val checked: Boolean)

/**
 * Resolve the declared [declaredMinimum] (null when absent) and [declaredMaximum] (A12's primitive default `0` when
 * absent). Only `-1` is an admissible negative member (KF559), so any negative member here is that sentinel.
 */
internal fun resolveFractionalPair(declaredMinimum: Int?, declaredMaximum: Int): FractionalPair {
    val minimum = declaredMinimum ?: 0
    return when {
        declaredMaximum < 0 -> FractionalPair(declaredMinimum?.coerceAtLeast(0), minimum.coerceAtLeast(0), checked = true)
        minimum < 0 && declaredMaximum == 0 -> FractionalPair(0, 0, checked = false)
        minimum < 0 -> FractionalPair(0, declaredMaximum, checked = true)
        else -> FractionalPair(declaredMinimum, declaredMaximum, checked = true)
    }
}

/**
 * The text a declared `minValue`/`maxValue` is compared as at runtime: A12 code generation holds the bound as its
 * nearest binary64 double and prints that double (KF564). When both RAW fractional members equal one `n ≥ 0` it
 * prints exactly `n` places, rounding the double's shortest decimal half up, so `5.555` on a fixed two-place field
 * becomes `5.56`; otherwise it prints the shortest decimal that reads back to the double, plain and without
 * trailing zeros, so `1.00000000000000001` becomes `1`.
 *
 * The fixed-places arm reads the SHORTEST decimal, as the Kernel's printer, Java's `%.nf`, does on JDK 21. JDK 17's
 * formatter took an older conversion's digits, one more for a few doubles, so there the Kernel itself prints
 * `282879384806159008` where this prints `282879384806159000` (INTERPRETER-FINDINGS IF420).
 *
 * Null [authored] text, or text that is no finite number, is returned unchanged: the value check then has no
 * readable bound, which is how an unreadable bound behaved before.
 */
internal fun printedNumberBound(authored: String?, declaredMinimum: Int?, declaredMaximum: Int): String? {
    val double = authored?.javaTrimmed()?.takeIf(::isA12DecimalText)?.toDouble()?.takeIf { it.isFinite() } ?: return authored
    val shortest = A12DocumentJson.canonicalNumberOrNull(double.toString()) ?: return authored
    val places = declaredMinimum ?: 0
    return if (places >= 0 && places == declaredMaximum) dec(shortest).displayAtScale(places)
    else dec(shortest).display()
}

/**
 * Whether Java-trimmed [text] is a decimal A12's reader binds to a `BigDecimal`: Jackson's
 * `NumberInput.looksLikeValidNumber` gate, ASCII digits only, so `"\u0665"` and `"abc"` are not (KF591). The
 * conversion behind it refuses text longer than [MAX_A12_NUMBER_TEXT] and a scale past `Int` too, so a 1001-digit
 * bound and `1e9999999999` leave no model while `1e2147483648` converts (KF593).
 */
internal fun isA12DecimalText(text: String): Boolean {
    if (text.length > MAX_A12_NUMBER_TEXT || !A12_DECIMAL_TEXT.matches(text)) return false
    val mantissa = text.substringBefore('e').substringBefore('E')
    val exponentText = text.substring(mantissa.length).drop(1)
    val exponent = if (exponentText.isEmpty()) 0L else exponentText.asciiLongOrNull() ?: return false
    return mantissa.substringAfter('.', "").length - exponent in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()
}

/** Jackson's default `StreamReadConstraints.maxNumberLength`, measured on a bound's text (KF593). */
private const val MAX_A12_NUMBER_TEXT = 1000

private val A12_DECIMAL_TEXT = Regex("[+-]?[0-9]*\\.?[0-9]+(?:[eE][+-]?[0-9]+)?|[+-]?[0-9]+\\.")
