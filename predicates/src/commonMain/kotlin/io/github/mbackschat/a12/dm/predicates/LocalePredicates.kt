package io.github.mbackschat.a12.dm.predicates

/**
 * The key A12 compares a locale spelling by, or null when its parser refuses the spelling and builds no model.
 *
 * A message entry's `locale` and a header's `locales[].code` both go through Apache Commons Lang's
 * `LocaleUtils.toLocale` (source-located at `LocalizedTextMapDeserializer` and `ModelHeaderUtils`), and A12
 * compares the resulting locales, so `en-US` and `en_US` are one key. The key is the locale's `toString` form:
 * `en`, `en_US`, `_US`, `en__POSIX`, `en_US_POSIX`, and `he` for `iw`, which the JDK reads as one language. The
 * empty spelling is the parser's "any locale" and keys as itself. The grammar is written from the parser's
 * measured verdicts, not ported from it ([KERNEL-FINDINGS KF649]); `adapter.laws.CheckerLocaleSpellingAgreementTest`
 * holds it to that jar spelling by spelling.
 *
 * **A spelling holding a non-ASCII character keys as written, never judged — deliberately lenient.** The parser
 * classifies letter case and digits by Java's `Character` tables, so `ée` and `de_٣٣٣` are read, while Kotlin/JS
 * answers from its own tables, so a JVM and a Node reading could disagree ([KERNEL-FINDINGS KF587]). Judging them
 * exactly on both would mean shipping Java's tables; keying them as written can only accept a spelling such as
 * `ÉÉ`, which A12 refuses, and never refuses one A12 reads.
 *
 * The Java kits read a header through it, and the checker reads both carriers through it.
 */
fun a12LocaleKey(code: String): String? = when {
    code.isEmpty() -> ""
    code.any { it > '\u007F' } -> code
    else -> spelledLocale(code)?.key
}

/**
 * Whether a [key] from [a12LocaleKey] names a language. A header locale without one, `_US`, `US` or the empty
 * code, is read and then fails A12's build ([KERNEL-FINDINGS KF649]); a message entry may name one freely.
 */
fun a12LocaleKeyHasLanguage(key: String): Boolean = key.isNotEmpty() && key.first() != '_' && key.first() != '-'

/**
 * The language tag `java.util.Locale.toLanguageTag` writes for the locale a header [key] from [a12LocaleKey] names,
 * or null for a key holding a non-ASCII character, which [a12LocaleKey] keeps as written and so never splits.
 *
 * A12 compares a model's declared locales by this tag, as written and case-sensitively: two distinct locales writing
 * one tag draw `MVK_SUPP_LANGUAGES_DUPLICATE` ([KERNEL-FINDINGS KF649]). A language of two to eight letters is
 * written and any other is `und`; the country is written. The variant's `_`-separated segments are written while
 * each is a BCP 47 variant, five to eight letters or digits or a digit and three more; from the first that is not,
 * they are written as private use, `x-lvariant-…`, while each has one to eight letters or digits, and the rest are
 * dropped, which is how `en_US_abcdefghi` writes `en-US`. Three legacy locales are the JDK's own: `no_NO_NY` writes
 * `nn-NO`, and `ja_JP_JP` and `th_TH_TH` carry a calendar or numbering extension. Written from the JDK's measured
 * output, not ported: `adapter.laws.CheckerLocaleSpellingAgreementTest` holds it to that method spelling by spelling.
 */
fun a12LanguageTag(key: String): String? {
    if (key.any { it > '\u007F' }) return null
    val parts = key.split('_', limit = 3)
    val country = parts.getOrElse(1) { "" }
    val nynorsk = key == "no_NO_NY"
    val segments = parts.getOrNull(2)?.takeUnless { nynorsk }?.split('_').orEmpty()
    val variants = segments.takeWhile(String::isVariantSubtag)
    val privateUse = segments.drop(variants.size).takeWhile(String::isPrivateUseSubtag)
    val language = if (nynorsk) "nn" else parts[0].takeIf(String::isLanguageSubtag) ?: "und"
    return (listOf(language) + listOf(country).filter(String::isNotEmpty) + variants + listOfNotNull(LEGACY_EXTENSIONS[key]) +
        if (privateUse.isEmpty()) emptyList() else listOf("x", "lvariant") + privateUse).joinToString("-")
}

/** A parsed locale, its language and country normalized as `java.util.Locale` normalizes them. */
private class SpelledLocale(spelledLanguage: String, spelledCountry: String, val variant: String) {
    val language: String = spelledLanguage.asciiLowercase().let { LEGACY_LANGUAGES[it] ?: it }
    val country: String = spelledCountry.asciiUppercase()

    val key: String = buildString {
        append(language)
        if (country.isNotEmpty() || variant.isNotEmpty()) append('_').append(country)
        if (variant.isNotEmpty()) append('_').append(variant)
    }
}

/**
 * The spelling's language, country and variant as the parser reads them, or null when it refuses the spelling.
 *
 * A spelling separates with `_`, or with `-` when it holds no `_`. Letter case and digits are read over ASCII
 * alone, where they are every target's ([a12LocaleKey] keeps a non-ASCII spelling from here). A lone segment is a
 * lowercase language of two or three letters, or an uppercase pair the JDK lists as a country. Two segments are
 * that language and an uppercase country, or ANY first segment before a three-digit area. Three are a language,
 * an empty or uppercase country or an area, and a nonempty variant that keeps any further separators. A spelling
 * opening with its separator names an uppercase country, optionally followed by the same separator and a
 * nonempty variant.
 */
private fun spelledLocale(code: String): SpelledLocale? {
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

private fun String.asciiLowercase() = buildString(length) { this@asciiLowercase.forEach { append(if (it in 'A'..'Z') it + ('a' - 'A') else it) } }

private fun String.asciiUppercase() = buildString(length) { this@asciiUppercase.forEach { append(if (it in 'a'..'z') it - ('a' - 'A') else it) } }

private fun String.isLanguageSubtag() = length in 2..8 && all { it in 'a'..'z' || it in 'A'..'Z' }

private fun String.isVariantSubtag() = when (length) {
    in 5..8 -> all(Char::isAsciiAlphanumeric)
    4 -> first() in '0'..'9' && all(Char::isAsciiAlphanumeric)
    else -> false
}

private fun String.isPrivateUseSubtag() = length in 1..8 && all(Char::isAsciiAlphanumeric)

private fun Char.isAsciiAlphanumeric() = this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'

/** The two locales the JDK's constructor gives an extension, which their language tags carry. */
private val LEGACY_EXTENSIONS = mapOf("ja_JP_JP" to "u-ca-japanese", "th_TH_TH" to "u-nu-thai")

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
