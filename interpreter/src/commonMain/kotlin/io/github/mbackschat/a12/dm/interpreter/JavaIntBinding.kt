package io.github.mbackschat.a12.dm.interpreter

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/**
 * How A12's reader binds one JSON member to a Java `int` or `Integer` (KERNEL-FINDINGS KF590, KF591, KF593).
 *
 * Text is read after Java's `trim`: the empty text and `null`, in that exact case, bind to no value, which an `int`
 * member holds as 0; any other text binds through `Integer.parseInt`, so a decimal digit of any script counts and a
 * fraction or an exponent does not. A JSON number binds when its exact value lies within `Int`'s range, and then
 * truncated toward zero, so `2147483647.5` binds nothing while `1e1` is 10. A Boolean, an object and an array never
 * bind (`RepeatabilityTextReadingDiffTest`).
 */
internal sealed interface JavaIntBinding {
    data class Bound(val value: Int) : JavaIntBinding
    data object NoValue : JavaIntBinding
    data object Unbindable : JavaIntBinding
}

/** [JavaIntBinding] of this member; an absent member holds no value. */
internal fun JsonElement?.bindJavaInt(): JavaIntBinding {
    if (this == null) return JavaIntBinding.NoValue
    val primitive = this as? JsonPrimitive ?: return JavaIntBinding.Unbindable
    if (primitive is JsonNull) return JavaIntBinding.NoValue
    if (primitive.isString) {
        val text = primitive.content.javaTrimmed()
        if (text.isEmpty() || text == "null") return JavaIntBinding.NoValue
        return text.javaIntOrNull()?.let { JavaIntBinding.Bound(it) } ?: JavaIntBinding.Unbindable
    }
    return primitive.content.jsonNumberAsJavaIntOrNull()?.let { JavaIntBinding.Bound(it) } ?: JavaIntBinding.Unbindable
}

/**
 * A JSON number lexeme's value truncated toward zero, or null when the exact value lies outside `Int`'s range, its
 * `BigDecimal` scale leaves `Int` (`RepeatabilityTextReadingDiffTest`), or the lexeme is no JSON number at all.
 */
internal fun String.jsonNumberAsJavaIntOrNull(): Int? {
    val match = JSON_NUMBER_PARTS.matchEntire(this) ?: return null
    val (sign, integerPart, fractionPart, exponentPart) = match.destructured
    val exponent = if (exponentPart.isEmpty()) 0L else exponentPart.asciiLongOrNull() ?: return null
    if (fractionPart.length - exponent !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) return null
    val all = integerPart + fractionPart
    val digits = all.trimStart('0')
    if (digits.isEmpty()) return 0
    // The decimal point's offset into [digits], once the exponent has moved it and the leading zeros are gone.
    val point = integerPart.length + exponent - (all.length - digits.length)
    val whole = when {
        point <= 0 -> return 0
        point > MAX_INT_DIGITS -> return null
        point >= digits.length -> digits + "0".repeat((point - digits.length).toInt())
        else -> digits.substring(0, point.toInt())
    }
    val fractional = point < digits.length && digits.substring(point.toInt()).any { it != '0' }
    val magnitude = whole.asciiLongOrNull() ?: return null
    val limit = if (sign == "-") -(Int.MIN_VALUE.toLong()) else Int.MAX_VALUE.toLong()
    if (magnitude > limit || magnitude == limit && fractional) return null
    return (if (sign == "-") -magnitude else magnitude).toInt()
}

private const val MAX_INT_DIGITS = 10

private val JSON_NUMBER_PARTS = Regex("(-?)(0|[1-9][0-9]*)(?:\\.([0-9]+))?(?:[eE]([-+]?[0-9]+))?")
