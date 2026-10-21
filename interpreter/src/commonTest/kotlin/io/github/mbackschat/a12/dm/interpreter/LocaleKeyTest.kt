package io.github.mbackschat.a12.dm.interpreter

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [a12LocaleKeyOrNull] against the spellings A12 was measured on (`rulekit.validate.laws.MessageSlotRawShapeLawsTest`),
 * the same rows the portable checker's `localeKeyOrNull` answers, so the held copy cannot drift from them.
 */
class LocaleKeyTest {

    @Test
    fun eachMeasuredSpellingReadsAsA12ReadsIt() {
        val accepted = mapOf("en_US" to "en_US", "en-US" to "en_US", "en" to "en", "eng" to "eng", "fr_CA" to "fr_CA",
            "en_US_POSIX" to "en_US_POSIX", "en_123" to "en_123")
        val refused = listOf("", "en_us", "EN_US", "EN", "e", "en_U", "en_USA", "klingon")
        assertEquals(accepted + refused.associateWith { null }, (accepted.keys + refused).associateWith(::a12LocaleKeyOrNull))
    }
}
