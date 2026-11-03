package io.github.mbackschat.a12.dm.consistency

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * How A12's reader binds one JSON scalar to a Java `Integer` (KERNEL-FINDINGS KF590, KF591, KF593); a primitive
 * `int` member reads through [bindJavaPrimitiveInt] instead.
 *
 * Text is read after Java's `trim`: the empty text and `null`, in that exact case, bind to no value; any other text
 * binds through `Integer.parseInt`, so a decimal digit of any script counts and a fraction or an exponent does not. A
 * JSON number binds when its exact value lies within `Int`'s range, and then truncated toward zero, so `2147483647.5`
 * binds nothing while `1e1` is 10. A Boolean never binds. The interpreter carries the same binding as its own
 * `JavaIntBinding`; its dependency gate admits no project edge, so the two cannot share one source, and
 * `RepeatabilityTextReadingDiffTest` holds both to A12.
 */
internal sealed interface JavaIntBinding {
    data class Bound(val value: Int) : JavaIntBinding
    data object NoValue : JavaIntBinding
    data object Unbindable : JavaIntBinding
}

internal fun JsonPrimitive.bindJavaInt(): JavaIntBinding {
    if (this is JsonNull) return JavaIntBinding.NoValue
    if (isString) {
        val text = content.javaTrimmed()
        if (text.isEmpty() || text == "null") return JavaIntBinding.NoValue
        return text.javaIntOrNull()?.let { JavaIntBinding.Bound(it) } ?: JavaIntBinding.Unbindable
    }
    return content.jsonNumberAsJavaIntOrNull()?.let { JavaIntBinding.Bound(it) } ?: JavaIntBinding.Unbindable
}

/**
 * [bindJavaInt] for a member A12 declares as a primitive `int` rather than an `Integer`. Kernel 31 reads through
 * Jackson 3, which refuses a null for a primitive, so a value that binds NO value as an `Integer` — the empty text,
 * the text `null`, a JSON null — leaves A12 with no model instead of a 0 (KERNEL-FINDINGS KF593, corrected
 * 2026-11-02). The bound value, or null where A12's reader cannot bind the member.
 */
internal fun JsonPrimitive.bindJavaPrimitiveInt(): Int? = (bindJavaInt() as? JavaIntBinding.Bound)?.value

/**
 * A stored integer member of a model object as A12's reader binds it, `" 2 "` and `2.7` included (KF590, KF593); null
 * where the member is absent, binds no value, or cannot be bound. The last two differ between a primitive `int` and an
 * `Integer` only in whether A12 builds the model, which `FieldTypeBinding` judges, so every reading site shares this.
 */
internal fun JsonObject.boundInt(member: String): Int? = (this[member] as? JsonPrimitive)?.bindJavaPrimitiveInt()

/**
 * A JSON number lexeme's value truncated toward zero, or null when the exact value lies outside `Int`'s range, its
 * `BigDecimal` scale leaves [A12_DECIMAL_SCALES], or the lexeme is no JSON number at all. The lexeme's digits are ASCII by JSON's
 * grammar, so [javaLongOrNull] reads them exactly.
 */
private fun String.jsonNumberAsJavaIntOrNull(): Int? {
    val match = JSON_NUMBER_PARTS.matchEntire(this) ?: return null
    val (sign, integerPart, fractionPart, exponentPart) = match.destructured
    val exponent = if (exponentPart.isEmpty()) 0L else exponentPart.javaLongOrNull() ?: return null
    if (fractionPart.length - exponent !in A12_DECIMAL_SCALES) return null
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
    val magnitude = whole.javaLongOrNull() ?: return null
    val limit = if (sign == "-") -(Int.MIN_VALUE.toLong()) else Int.MAX_VALUE.toLong()
    if (magnitude > limit || magnitude == limit && fractional) return null
    return (if (sign == "-") -magnitude else magnitude).toInt()
}

private const val MAX_INT_DIGITS = 10

private val JSON_NUMBER_PARTS = Regex("(-?)(0|[1-9][0-9]*)(?:\\.([0-9]+))?(?:[eE]([-+]?[0-9]+))?")
