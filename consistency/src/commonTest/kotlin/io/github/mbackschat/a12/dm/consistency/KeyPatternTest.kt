package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The declared-pattern subset on both targets. Each expected match is Java's own `String.matches` on the same
 * pattern and value, executed on JDK 21.0.12 on 2026-09-26; `adapter.laws.CheckerIndexKeyConstantAgreementTest`
 * asks the Kernel for its rows. This also locks the parser's boundary, which no Kernel row can, since a pattern
 * outside it is simply left to A12.
 */
class KeyPatternTest {

    private fun matches(pattern: String, value: String): Boolean? = KeyPattern.parse(pattern)?.matches(value)

    @Test
    fun theSubsetMatchesTheWholeValue() {
        assertEquals(
            listOf(true, false, false, true, false, false, true, true, false, true, false, true, false),
            listOf(
                matches("[A-Z]{2}[0-9]", "AB1"), matches("[A-Z]{2}[0-9]", "XAB1"), matches("[A-Z]{2}[0-9]", "ab1"),
                matches("[A-Z]+-[0-9]{2,3}", "AB-12"), matches("[A-Z]+-[0-9]{2,3}", "AB-1234"),
                matches("[A-Z]+-[0-9]{2,3}", "-12"),
                matches("[^0-9]*", "abc"), matches("[a-z]?[0-9]*", "5"), matches("[a-z]?[0-9]*", "ab"),
                matches("[0-9]{3,}", "12345"), matches("[0-9]{3,}", "12"),
                matches("a_b c", "a_b c"), matches("[a-c ]+", "abd"),
            ),
        )
    }

    /** Java matches code points, so a surrogate pair is one character and a lone surrogate is one too. */
    @Test
    fun aSupplementaryCharacterIsOneCharacter() {
        val grin = "\uD83D\uDE00"
        assertEquals(
            listOf(false, true, true, true, true),
            listOf(
                matches("[^0-9]{2}", grin), matches("[^0-9]", grin), matches("[^0-9]{2}", grin + "a"),
                matches("[^0-9]", "\uD83D"), matches("[^0-9]{2}", "\uD83Da"),
            ),
        )
    }

    /** Where Java and JavaScript regex part ways, or where a regex engine would be needed, the parser declines. */
    @Test
    fun everythingElseIsOutsideTheSubset() {
        for (pattern in listOf(".{2}", "\\d+", "(AB)+", "A|B", "^AB$", "[a-z&&[^c]]", "[-a]", "[z-a]", "[]",
                "A+?", "A++", "A{,3}", "A{3,2}", "A{2", "[A-Z")) {
            assertNull(KeyPattern.parse(pattern), pattern)
        }
    }
}
