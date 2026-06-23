package io.github.mbackschat.a12.dm.interpreter

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Cross-platform lock for [Dec.roundTo] — runs on JVM **and** JS (Node). The expected values are the kernel's
 * (the `:adapter` `RoundDiffTest` proved them against the live kernel on the JVM); this test re-asserts them on
 * **JS**, where big.js has no native FLOOR/CEILING and [Dec.roundTo] emulates them by sign. The negative cases
 * are the ones that distinguish FLOOR/CEILING from round-toward/away-zero, and the `.5` cases catch half-even.
 */
class RoundToTest {

    private fun round(value: String, places: Int, mode: RoundMode): String =
        dec(value).roundTo(places, mode).display()

    @Test fun floorRoundsTowardNegativeInfinity() {
        assertEquals("-2", round("-1.4", 0, RoundMode.FLOOR), "FLOOR(-1.4, 0)")
        assertEquals("1", round("1.8", 0, RoundMode.FLOOR), "FLOOR(1.8, 0)")
        assertEquals("-1.5", round("-1.45", 1, RoundMode.FLOOR), "FLOOR(-1.45, 1)")
    }

    @Test fun ceilingRoundsTowardPositiveInfinity() {
        assertEquals("-1", round("-1.4", 0, RoundMode.CEILING), "CEILING(-1.4, 0)")
        assertEquals("2", round("1.2", 0, RoundMode.CEILING), "CEILING(1.2, 0)")
        assertEquals("-1.4", round("-1.45", 1, RoundMode.CEILING), "CEILING(-1.45, 1)")
    }

    @Test fun halfUpRoundsAwayFromZeroOnTheBoundary() {
        assertEquals("3", round("2.5", 0, RoundMode.HALF_UP), "HALF_UP(2.5, 0)")
        assertEquals("-3", round("-2.5", 0, RoundMode.HALF_UP), "HALF_UP(-2.5, 0)")
        assertEquals("4.7", round("4.65", 1, RoundMode.HALF_UP), "HALF_UP(4.65, 1)")
    }

    @Test fun preRoundToScale19AvoidsArtefacts() {
        // 1/3 + 2/3 at MathContext(50) is 0.999…9; the kernel's two-step pre-rounds to scale 19 HALF_UP → 1,
        // so FLOOR to 0 places is 1, NOT 0 (the artefact the BigDecimalUtils.runden comment warns about).
        val third = dec("1").div(dec("3"))
        val twoThirds = dec("2").div(dec("3"))
        assertEquals("1", (third + twoThirds).roundTo(0, RoundMode.FLOOR).display(), "FLOOR(1/3 + 2/3, 0)")
    }
}
