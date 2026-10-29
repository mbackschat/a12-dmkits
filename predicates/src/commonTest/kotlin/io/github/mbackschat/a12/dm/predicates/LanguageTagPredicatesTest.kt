package io.github.mbackschat.a12.dm.predicates

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The language tag of a header locale, one row per clause, on both runtimes. Each tag is `Locale.toLanguageTag`'s on
 * OpenJDK 21.0.13 (2026-10-29); `adapter.laws.CheckerLocaleSpellingAgreementTest` holds the function to that method
 * over generated spellings, which only the JVM can run.
 */
class LanguageTagPredicatesTest {

    @Test
    fun aHeaderSpellingWritesTheJdksTag() {
        val rows = linkedMapOf(
            "de" to "de", "deu" to "deu", "DE_419" to "de-419", "de-CH" to "de-CH", "iw" to "he",
            "de_CH_1996" to "de-CH-1996", "de__POSIX" to "de-POSIX", "de_CH_POSIX_WIN32" to "de-CH-POSIX-WIN32",
            "de_CH_posix" to "de-CH-posix", "de_CH_x" to "de-CH-x-lvariant-x", "de_CH_1996_x" to "de-CH-1996-x-lvariant-x",
            "en_US_abcdefghi" to "en-US", "de_CH_x_abcdefghi" to "de-CH-x-lvariant-x", "de_CH_POSIX-x" to "de-CH",
            "abcd_419" to "abcd-419", "abcdefghi_419" to "und-419", "1_419" to "und-419",
            "no_NO_NY" to "nn-NO", "ja_JP_JP" to "ja-JP-u-ca-japanese-x-lvariant-JP", "th_TH_TH" to "th-TH-u-nu-thai-x-lvariant-TH",
            "ja_JP_jp" to "ja-JP-x-lvariant-jp",
        )
        assertEquals<Map<String, String?>>(rows, rows.keys.associateWith { a12LanguageTag(a12LocaleKey(it)!!) })
    }

    @Test
    fun aNonAsciiLocaleWritesNoTag() {
        assertEquals(null, a12LanguageTag(a12LocaleKey("de_ÄÖ")!!))
    }
}
