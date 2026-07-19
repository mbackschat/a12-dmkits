package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.model.LegalCharset
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Deterministic JVM + Node catastrophe guard for the exact production matcher. Fourfold input growth must stay
 * below the ratified 9x catastrophe ceiling; the quadratic control proves the assertion is capable of failing.
 */
class LegalCharsetGrowthTest {

    @Test
    fun bmpAndGraphemeMatchingStayBelowTheGrowthCeiling() {
        val unit = "Cafe$E_ACUTE"
        val n = unit.repeat(BASE_UNITS)
        val fourN = unit.repeat(BASE_UNITS * 4)
        val graphemes = LegalCharset.of(
            ranges = listOf(0x20..0x7E),
            graphemes = listOf(E_ACUTE),
        )

        assertTrue(LegalCharset.BMP.matches(n))
        assertTrue(LegalCharset.BMP.matches(fourN))
        assertTrue(graphemes.matches(n))
        assertTrue(graphemes.matches(fourN))

        assertTrue(
            belowCatastropheCeiling(LegalCharset.BMP.matchWork(n), LegalCharset.BMP.matchWork(fourN)),
            "BMP scan must grow below 9x for a 4x input",
        )
        assertTrue(
            belowCatastropheCeiling(graphemes.matchWork(n), graphemes.matchWork(fourN)),
            "grapheme-trie scan must grow below 9x for a 4x input",
        )
    }

    @Test
    fun theGrowthAssertionRejectsAQuadraticControl() {
        val n = BASE_UNITS.toLong() * BASE_UNITS
        val fourN = (BASE_UNITS * 4L) * (BASE_UNITS * 4L)
        assertFalse(belowCatastropheCeiling(n, fourN), "a 16x quadratic curve must fail the <9x ceiling")
    }

    private fun belowCatastropheCeiling(n: Int, fourN: Int): Boolean =
        belowCatastropheCeiling(n.toLong(), fourN.toLong())

    private fun belowCatastropheCeiling(n: Long, fourN: Long): Boolean = fourN < n * CATASTROPHE_CEILING

    private companion object {
        const val BASE_UNITS = 1_000
        const val CATASTROPHE_CEILING = 9
        const val ACUTE = "\u0301"
        const val E_ACUTE = "e$ACUTE"
    }
}
