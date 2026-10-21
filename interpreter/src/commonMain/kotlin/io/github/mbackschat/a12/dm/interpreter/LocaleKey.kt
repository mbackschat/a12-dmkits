package io.github.mbackschat.a12.dm.interpreter

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A declared locale code, reduced to the key A12 compares locales by, or null for a code A12's reader refuses.
 *
 * A12 parses a code into a locale value rather than comparing its spelling, so `en-US` and `en_US` are one locale,
 * while `en_us`, `EN_US`, `EN`, `e`, `en_U`, `en_USA` and `klingon` are refused outright (measured by
 * `rulekit.validate.laws.MessageSlotRawShapeLawsTest`). This is a held copy of the portable checker's
 * `localeKeyOrNull`, which the interpreter may not depend on; `LocaleKeyTest` holds it to the same measured rows.
 * The empty code is read by A12 but covers no locale, so it is no key either.
 */
internal fun a12LocaleKeyOrNull(code: String): String? {
    if (code.isEmpty()) return null
    val segments = code.split('_', '-')
    val language = segments[0]
    if (language.length !in 2..3 || !language.all { it in 'a'..'z' }) return null
    if (segments.size == 1) return language
    val region = segments[1]
    val isCountry = region.length == 2 && region.all { it in 'A'..'Z' }
    val isNumericArea = region.length == 3 && region.all { it in '0'..'9' }
    if (!isCountry && !isNumericArea) return null
    // A variant beyond the region is free-form (`en_US_POSIX` is admitted), so it joins the key unexamined.
    return segments.joinToString("_")
}

/** The keys of the locales a model's `header.locales` declares, each entry an object with a `code` or a bare text. */
internal fun JsonObject.declaredLocaleKeys(): Set<String> =
    ((this["header"] as? JsonObject)?.get("locales") as? JsonArray).orEmpty().mapNotNullTo(linkedSetOf()) { entry ->
        val code = when (entry) {
            is JsonObject -> (entry["code"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            is JsonPrimitive -> entry.takeIf { it.isString }?.content
            else -> null
        }
        code?.let(::a12LocaleKeyOrNull)
    }
