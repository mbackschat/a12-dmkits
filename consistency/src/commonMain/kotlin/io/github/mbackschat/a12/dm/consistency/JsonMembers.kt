package io.github.mbackschat.a12.dm.consistency

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A member A12 binds to a primitive `boolean`, read the way A12's reader binds it rather than the way JSON
 * types it; `false` when the member is absent, which is every such member's declared default.
 *
 * Measured on `StringType`'s two flags ([KERNEL-FINDINGS KF569],
 * `adapter.laws.CheckerDeclarationAdmissionAgreementTest`, and the ordering-key rows of
 * `rulekit.validate.laws.GroupOrderingFieldTypeLawsTest`): the literals bind as themselves and an explicit JSON
 * `null` as the default; an integer binds as "not zero", so `2` and `-1` set the flag; the strings `"true"`,
 * `"True"`, `"TRUE"` set it and `"false"`, `"False"`, `"FALSE"` and `""` clear it. **A null result is the
 * answer A12 has no binding for**, and a law asking this reader must then say nothing: a fractional number, or a
 * string such as `"1"`, `"tRUE"` or `"yes"`, refuses the reader outright, so the model is never built and no
 * coded class exists ([unbindableFlag] reports that). A padded, blank or `"null"` string reads as null too,
 * because none has been put to A12.
 *
 * The other primitive `boolean` members (`positivesOnly`) are read through the same function: they are the
 * same Java type on the same reader, so the binding is the reader's rather than the member's.
 */
internal fun JsonObject.flag(member: String): Boolean? {
    val value = this[member] ?: return false
    if (value is JsonNull) return false
    val primitive = value as? JsonPrimitive ?: return null
    return when {
        primitive.isString -> when (primitive.content) {
            "true", "True", "TRUE" -> true
            "false", "False", "FALSE", "" -> false
            else -> null
        }
        primitive.content == "true" -> true
        primitive.content == "false" -> false
        INTEGER.matches(primitive.content) -> primitive.content.any { it in '1'..'9' }
        else -> null
    }
}

/**
 * Whether [member] holds a value A12's `boolean` reader refuses outright, so the model never loads: a
 * fractional or exponent number, or a non-blank, unpadded string that is neither a boolean spelling [flag]
 * binds nor `"null"`. The measured rows are `1.0`, `1.5`, `"1"`, `"tRUE"` and `"yes"`; an array and an object
 * are left out, unmeasured.
 */
internal fun JsonObject.unbindableFlag(member: String): Boolean {
    val value = this[member] ?: return false
    val primitive = value as? JsonPrimitive ?: return false
    if (primitive.isString) {
        val text = primitive.content
        return flag(member) == null && text.isNotBlank() && text == text.trim() && text != "null"
    }
    return flag(member) == null
}

private val INTEGER = Regex("-?[0-9]+")
