package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.model.LegalCharset
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

/**
 * The public charset factories share the pinned-kernel definition boundary. Invalid definitions fail during
 * construction, before they can create a zero-progress or unbounded trie; valid non-ambiguous prefix shapes stay
 * available on both JVM and Node.
 */
class LegalCharsetDefinitionTest {

    @Test
    fun anEmptyDefinitionListKeepsTheKernelsBmpDefault() {
        for (charset in listOf(LegalCharset.of(), LegalCharset.ofRanges())) {
            assertTrue(charset.matches("café"))
            assertFalse(charset.matches(EMOJI))
        }
    }

    @Test
    fun emptyEntriesAreRejectedByBothFactories() {
        assertFailsWith<IllegalArgumentException> {
            LegalCharset.of(graphemes = listOf(""))
        }
        assertFailsWith<IllegalArgumentException> {
            LegalCharset.ofSupportedCharacters(listOf(""))
        }
    }

    @Test
    fun surrogateBearingEntriesAreRejectedByBothFactories() {
        assertBothFactoriesReject(listOf(EMOJI))
    }

    @Test
    fun overlongEntriesAreRejectedByBothFactories() {
        assertBothFactoriesReject(listOf("e$ACUTE$GRAVE$CEDILLA"))
    }

    @Test
    fun plainMultiCharacterEntriesAreRejectedByBothFactories() {
        assertBothFactoriesReject(listOf("ab"))
    }

    @Test
    fun reversedRangesAreRejectedByBothFactories() {
        assertFailsWith<IllegalArgumentException> {
            LegalCharset.of(ranges = listOf('Z'.code..'A'.code))
        }
        assertFailsWith<IllegalArgumentException> {
            LegalCharset.ofSupportedCharacters(listOf("Z-A"))
        }
    }

    @Test
    fun thePinnedKernelsAmbiguousOverlapShapeIsRejectedByBothFactories() {
        val definitions = listOf("q${ACUTE}r", "q$ACUTE", "r$GRAVE")
        assertBothFactoriesReject(definitions)
    }

    @Test
    fun sharedPrefixesAreLegalAndDoNotLeakTheirComponents() {
        val definitions = listOf(E_ACUTE, E_GRAVE)
        assertBothFactories(definitions) { charset ->
            assertTrue(charset.matches(E_ACUTE))
            assertTrue(charset.matches(E_GRAVE))
            assertFalse(charset.matches("e"))
            assertFalse(charset.matches(ACUTE))
            assertFalse(charset.matches(GRAVE))
        }
    }

    @Test
    fun aCompleteGraphemeMayBeTheTerminalPrefixOfAnother() {
        val definitions = listOf(E_ACUTE, "${E_ACUTE}x")
        assertBothFactories(definitions) { charset ->
            assertTrue(charset.matches(E_ACUTE))
            assertTrue(charset.matches("${E_ACUTE}x"))
            assertFalse(charset.matches("e"))
            assertFalse(charset.matches("x"))
        }
    }

    @Test
    fun validMatchingAlwaysMakesProgress() {
        val charset = LegalCharset.of(graphemes = listOf(E_ACUTE, E_GRAVE))
        assertTrue(charset.matches((E_ACUTE + E_GRAVE).repeat(2_000)))
    }

    private fun assertBothFactoriesReject(definitions: List<String>) {
        assertFailsWith<IllegalArgumentException> {
            LegalCharset.of(graphemes = definitions)
        }
        assertFailsWith<IllegalArgumentException> {
            LegalCharset.ofSupportedCharacters(definitions)
        }
    }

    private fun assertBothFactories(definitions: List<String>, assertion: (LegalCharset) -> Unit) {
        assertion(LegalCharset.of(graphemes = definitions))
        assertion(LegalCharset.ofSupportedCharacters(definitions)!!)
    }

    private companion object {
        const val ACUTE = "\u0301"
        const val GRAVE = "\u0300"
        const val CEDILLA = "\u0327"
        const val E_ACUTE = "e$ACUTE"
        const val E_GRAVE = "e$GRAVE"
        const val EMOJI = "\uD83D\uDE00"
    }
}
