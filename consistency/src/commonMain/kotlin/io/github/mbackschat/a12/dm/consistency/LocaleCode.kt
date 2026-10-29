package io.github.mbackschat.a12.dm.consistency

/**
 * A declared or authored locale code, reduced to the key A12 compares locales by.
 *
 * **A12 does not compare the spelling.** It parses both a header's `locales[].code` and a message's `locale`
 * into a locale value and compares those, so `en-US` and `en_US` are ONE locale and a checker comparing text
 * reports a coverage gap for a model A12 admits — the direction the Kernel is never asked about. Measured
 * 2026-09-12 by `rulekit.validate.laws.MessageSlotRawShapeLawsTest`, on both the header and the message side.
 * The key is the locale's own `toString` form, so `iw` and `he`, which the JDK reads as one language, share one.
 *
 * **And the codes it refuses are refused outright**, not read leniently: a spelling the parser refuses makes
 * the model UNREADABLE, so normalizing it here would analyse a model that does not load. That is why this
 * returns null rather than a best effort.
 *
 * The parser A12 reaches is Apache Commons Lang's `LocaleUtils.toLocale` (source-located at
 * `LocalizedTextMapDeserializer.getLocaleFromLocalizedText` and `ModelHeaderUtils`). The grammar below is
 * written from its measured verdicts, not ported from it, and `adapter.laws.CheckerLocaleSpellingAgreementTest`
 * holds it to that jar spelling by spelling (KERNEL-FINDINGS KF649).
 *
 * **A spelling holding a non-ASCII character is read as written, never judged — deliberately lenient.** The
 * parser classifies letter case and digits by Java's `Character` tables, so `ée` and `de_٣٣٣` are read, and
 * neither target's Kotlin tables are Java's (KF587). Judging them exactly would mean shipping Java's tables;
 * reading them as written can only under-report a spelling such as `ÉÉ`, which A12 refuses, and never refuses
 * a model A12 builds. No declared locale can share such a key, since a header code must be ASCII.
 */
internal fun localeKeyOrNull(code: String): String? {
    if (code.isEmpty()) {
        // A12 READS the empty code — it is the "any locale" value — and refuses the model a tier later,
        // when its own build finds no language declared. It covers nothing, so it is not a locale key; the
        // caller reports it with the spellings the reader refuses, both being a model A12 will not use.
        return null
    }
    if (code.any { it > '\u007F' }) return code
    return spelledLocale(code)?.key
}

/**
 * A header's declared locale [code], reduced to its key, or null when A12 builds no model from it.
 *
 * The header parses its codes as a message entry does and then demands a well-formed language tag of the
 * result, which the entry never asks for (KERNEL-FINDINGS KF649): a language of two to eight ASCII letters,
 * a country of two ASCII letters or three ASCII digits, and a variant only beside a country, each of its
 * subtags five to eight ASCII letters or digits, or four opening with a digit. So `_US` and `ée`, which a
 * message entry may name, leave no model when a header declares them, and `XX_123` declares `xx_123`.
 */
internal fun headerLocaleKeyOrNull(code: String): String? =
    spelledLocale(code)?.takeIf { it.isWellFormedTag() }?.key

/** A parsed locale, its language and country normalized as `java.util.Locale` normalizes them. */
private class SpelledLocale(spelledLanguage: String, spelledCountry: String, val variant: String) {
    val language: String = spelledLanguage.asciiLowercase().let { LEGACY_LANGUAGES[it] ?: it }
    val country: String = spelledCountry.asciiUppercase()

    /** The locale's `toString` form: `en`, `en_US`, `_US`, `en__POSIX`, `en_US_POSIX`. */
    val key: String = buildString {
        append(language)
        if (country.isNotEmpty() || variant.isNotEmpty()) append('_').append(country)
        if (variant.isNotEmpty()) append('_').append(variant)
    }

    fun isWellFormedTag(): Boolean =
        language.length in 2..8 && language.all { it.isAsciiLetter() } &&
            (country.isEmpty() || country.length == 2 && country.all { it.isAsciiLetter() } || country.length == 3 && country.all { it in '0'..'9' }) &&
            (variant.isEmpty() || country.isNotEmpty() && variant.split('_', '-').all { it.isVariantSubtag() })
}

/**
 * The spelling's language, country and variant as the parser reads them, or null when it refuses the spelling.
 *
 * A spelling separates with `_`, or with `-` when it holds no `_`. Letter case and digits are read over ASCII
 * alone, where they are every target's (the caller keeps a non-ASCII spelling from here). A lone segment is a lowercase language of two or three letters, or an uppercase pair the JDK lists
 * as a country. Two segments are that language and an uppercase country, or ANY first segment before a
 * three-digit area. Three are a language, an empty or uppercase country or an area, and a nonempty variant
 * that keeps any further separators. A spelling opening with its separator names an uppercase country,
 * optionally followed by the same separator and a nonempty variant.
 */
private fun spelledLocale(code: String): SpelledLocale? {
    if (code.isEmpty()) return SpelledLocale("", "", "")
    val opening = code.first()
    if (opening == '_' || opening == '-') {
        val parts = code.substring(1).split(opening, limit = 2)
        val variant = parts.getOrElse(1) { "" }
        return SpelledLocale("", parts[0], variant).takeIf { parts[0].isCountry() && (parts.size == 1 || variant.isNotEmpty()) }
    }
    val parts = code.split(if ('_' in code) '_' else '-', limit = 3)
    return when (parts.size) {
        1 -> when {
            code.isLanguage() -> SpelledLocale(code, "", "")
            code in ISO_COUNTRIES -> SpelledLocale("", code, "")
            else -> null
        }
        2 -> SpelledLocale(parts[0], parts[1], "").takeIf {
            parts[1].isArea() || parts[0].isLanguage() && parts[1].isCountry()
        }
        else -> SpelledLocale(parts[0], parts[1], parts[2]).takeIf {
            parts[0].isLanguage() && (parts[1].isEmpty() || parts[1].isCountry() || parts[1].isArea()) && parts[2].isNotEmpty()
        }
    }
}

private fun String.isLanguage() = length in 2..3 && all { it in 'a'..'z' }

private fun String.isCountry() = length == 2 && all { it in 'A'..'Z' }

private fun String.isArea() = length == 3 && all { it in '0'..'9' }

private fun String.isVariantSubtag() = all { it.isAsciiLetter() || it in '0'..'9' } &&
    (length in 5..8 || length == 4 && first() in '0'..'9')

/** The withdrawn ISO 639 codes the JDK reads as their successors, so `iw` and `he` are one locale. */
private val LEGACY_LANGUAGES = mapOf("iw" to "he", "ji" to "yi", "in" to "id")

/**
 * The countries `java.util.Locale.getISOCountries()` lists on OpenJDK 21.0.13, which decide a bare uppercase
 * pair; `CountryListJvmTest` holds this to the running JDK.
 */
internal val ISO_COUNTRIES: Set<String> = """
    AD AE AF AG AI AL AM AO AQ AR AS AT AU AW AX AZ BA BB BD BE BF BG BH BI BJ BL BM BN BO BQ BR BS BT BV BW BY BZ
    CA CC CD CF CG CH CI CK CL CM CN CO CR CU CV CW CX CY CZ DE DJ DK DM DO DZ EC EE EG EH ER ES ET FI FJ FK FM FO FR
    GA GB GD GE GF GG GH GI GL GM GN GP GQ GR GS GT GU GW GY HK HM HN HR HT HU ID IE IL IM IN IO IQ IR IS IT JE JM JO
    JP KE KG KH KI KM KN KP KR KW KY KZ LA LB LC LI LK LR LS LT LU LV LY MA MC MD ME MF MG MH MK ML MM MN MO MP MQ MR
    MS MT MU MV MW MX MY MZ NA NC NE NF NG NI NL NO NP NR NU NZ OM PA PE PF PG PH PK PL PM PN PR PS PT PW PY QA RE RO
    RS RU RW SA SB SC SD SE SG SH SI SJ SK SL SM SN SO SR SS ST SV SX SY SZ TC TD TF TG TH TJ TK TL TM TN TO TR TT TV
    TW TZ UA UG UM US UY UZ VA VC VE VG VI VN VU WF WS YE YT ZA ZM ZW
""".split(' ', '\n').filter { it.isNotEmpty() }.toSet()
