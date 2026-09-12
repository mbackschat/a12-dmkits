package io.github.mbackschat.a12.dm.consistency

/**
 * A declared or authored locale code, reduced to the key A12 compares locales by.
 *
 * **A12 does not compare the spelling.** It parses both a header's `locales[].code` and a message's `locale`
 * into a locale value and compares those, so `en-US` and `en_US` are ONE locale and a checker comparing text
 * reports a coverage gap for a model A12 admits — the direction the Kernel is never asked about. Measured
 * 2026-09-12 by `rulekit.validate.laws.MessageSlotRawShapeLawsTest`, on both the header and the message side.
 *
 * **And the codes it refuses are refused outright**, not read leniently: `en_us`, `EN_US`, `EN`, `e`, `en_U`,
 * `en_USA` and `klingon` all make the model UNREADABLE, so normalizing case here would analyse a model that
 * does not load. That is why this returns null rather than a best effort. The two sides were varied
 * INDEPENDENTLY — a table moving both at once attributes every refusal to the header and establishes nothing
 * about a message's own `locale`, which is the shape a checker would then be free to compare as text.
 *
 * **Recall limit, stated because the shape is inferred rather than transcribed.** The parser A12 reaches is
 * Apache Commons Lang's `LocaleUtils.toLocale`, source-located at
 * `LocalizedTextMapDeserializer.getLocaleFromLocalizedText`; the rule below reproduces the fifteen measured
 * spellings — `en_US`, `en-US`, `en`, `eng`, `fr_CA`, `en_US_POSIX` and `en_123` accepted; `en_us`, `EN_US`,
 * `EN`, `e`, `en_U`, `en_USA` and `klingon` refused; the empty code read but then unbuildable — and is not a
 * port of that implementation, so a spelling outside the measured set may be classified wrongly. Both wrong
 * answers under-report: a refused-but-valid code drops a declared locale, and an accepted-but-invalid one
 * analyses a model that will not load. Neither is silent — a refused code is reported.
 */
internal fun localeKeyOrNull(code: String): String? {
    if (code.isEmpty()) {
        // A12 READS the empty code — it is the "any locale" value — and refuses the model a tier later,
        // when its own build finds no language declared. It covers nothing, so it is not a locale key; the
        // caller reports it with the spellings the reader refuses, both being a model A12 will not use.
        return null
    }
    val segments = code.split('_', '-')
    val language = segments[0]
    if (language.length !in 2..3 || !language.all { it in 'a'..'z' }) {
        return null
    }
    if (segments.size == 1) {
        return language
    }
    val region = segments[1]
    val isCountry = region.length == 2 && region.all { it in 'A'..'Z' }
    val isNumericArea = region.length == 3 && region.all { it in '0'..'9' }
    if (!isCountry && !isNumericArea) {
        return null
    }
    // A variant beyond the region is free-form (`en_US_POSIX` is admitted), so it joins the key unexamined.
    return segments.joinToString("_")
}
