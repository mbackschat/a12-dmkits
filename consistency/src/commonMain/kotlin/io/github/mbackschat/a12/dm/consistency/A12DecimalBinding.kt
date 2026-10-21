package io.github.mbackschat.a12.dm.consistency

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/**
 * How A12's reader binds a NUMBER field's `minValue` or `maxValue` to a `BigDecimal` (KERNEL-FINDINGS KF591, KF593).
 *
 * Text is read after Java's `trim`: the empty text and `null` declare no bound, and any other text must spell an
 * ASCII decimal of at most 1000 characters whose scale fits `Int`, so `"5."`, `".5"`, `"+5"` and `"1e2147483648"`
 * bind while `"٥"`, `"abc"`, a 1001-digit text and `"1e9999999999"` leave no model. A JSON number binds as
 * written. A Boolean or a container was not measured and reads here as no bound, the silent direction. The
 * interpreter carries the same rule as `isA12DecimalText`; its dependency gate admits no project edge, so the two
 * cannot share one source, and `adapter.laws.ModelTextReadingDiffTest` holds both to A12.
 */
internal sealed interface A12DecimalBinding {
    /** A bound, as ASCII decimal text; [value] reads it identically on every target, its digits being ASCII. */
    data class Bound(val text: String) : A12DecimalBinding {
        val value: Double get() = text.toDouble()
    }
    data object NoValue : A12DecimalBinding
    data object Unbindable : A12DecimalBinding
}

internal fun JsonPrimitive.bindA12Decimal(): A12DecimalBinding {
    if (this is JsonNull) return A12DecimalBinding.NoValue
    if (!isString) {
        return if (content == "true" || content == "false") A12DecimalBinding.NoValue else A12DecimalBinding.Bound(content)
    }
    val text = content.javaTrimmed()
    if (text.isEmpty() || text == "null") return A12DecimalBinding.NoValue
    return if (text.isA12DecimalText()) A12DecimalBinding.Bound(text) else A12DecimalBinding.Unbindable
}

/** The bound A12 reads from this member, or null when it reads none or the model is not built. */
internal fun JsonPrimitive.a12DecimalOrNull(): Double? = (bindA12Decimal() as? A12DecimalBinding.Bound)?.value

private fun String.isA12DecimalText(): Boolean {
    if (length > MAX_A12_NUMBER_TEXT || !A12_DECIMAL_TEXT.matches(this)) return false
    val mantissa = substringBefore('e').substringBefore('E')
    val exponentText = substring(mantissa.length).drop(1)
    val exponent = if (exponentText.isEmpty()) 0L else exponentText.javaLongOrNull() ?: return false
    return mantissa.substringAfter('.', "").length - exponent in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()
}

/** Jackson's default `StreamReadConstraints.maxNumberLength`, measured on a bound's text (KF593). */
private const val MAX_A12_NUMBER_TEXT = 1000

private val A12_DECIMAL_TEXT = Regex("[+-]?[0-9]*\\.?[0-9]+(?:[eE][+-]?[0-9]+)?|[+-]?[0-9]+\\.")
