package io.github.mbackschat.a12.dm.interpreter

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Cross-platform lock for [Dec.compareScaled19] — the allocation-light form of the kernel's scale-19 NUMBER
 * comparison (`BedingungsOperatorHelper.vergleiche` pre-rounds BOTH operands to scale 19 HALF_UP before
 * comparing). The contract is **bit-identical** to the explicit `scale19().compareTo(scale19())`: the fast
 * path may only skip the rounding *allocation* when it cannot change the outcome (≤19 fractional digits),
 * never the outcome itself. These cases pin both the sign AND the equivalence to the explicit form, including
 * the >19-digit cases where the scale-19 rounding genuinely changes the comparison result.
 */
class CompareScaled19Test {

    private fun signOf(i: Int) = if (i < 0) -1 else if (i > 0) 1 else 0

    /** The reference: the explicit two-allocation form the fast path must reproduce bit-for-bit. */
    private fun reference(a: String, b: String) = signOf(dec(a).scale19().compareTo(dec(b).scale19()))

    private fun fast(a: String, b: String) = signOf(dec(a).compareScaled19(dec(b)))

    private fun assertSame(expectedSign: Int, a: String, b: String) {
        assertEquals(expectedSign, reference(a, b), "reference scale19().compareTo() for $a vs $b")
        assertEquals(expectedSign, fast(a, b), "compareScaled19($a, $b)")
    }

    @Test fun integersAndEqualValuesAcrossScales() {
        assertSame(-1, "1", "50")
        assertSame(1, "50", "1")
        assertSame(0, "2.0", "2.00")          // equal value, different scale — compareTo ignores scale
        assertSame(0, "0", "0.0000")
        assertSame(-1, "-3", "2")
    }

    @Test fun fractionalWithinNineteenDigits() {
        assertSame(1, "0.1234567890123456789", "0.1234567890123456788")
        assertSame(0, "0.1000000000000000000", "0.1")   // 19 digits vs 1 digit, same value
    }

    @Test fun beyondNineteenDigitsRoundsToEqual() {
        // Differ only at the 20th fractional digit, both round (HALF_UP, 20th digit <5) to the same scale-19
        // value → equal. Raw compareTo would say -1; the scale-19 rounding makes them equal. THIS is the case
        // the fast path must NOT shortcut wrongly.
        assertSame(0, "0.12345678901234567890", "0.12345678901234567891")
    }

    @Test fun beyondNineteenDigitsRoundsApart() {
        // 20th digit 5 → HALF_UP rounds the first up to …6790; the second (no 20th digit) stays …6789 → +1.
        assertSame(1, "0.12345678901234567895", "0.1234567890123456789")
        // Mirror: the rounded-up value is the second operand → −1.
        assertSame(-1, "0.1234567890123456789", "0.12345678901234567895")
    }
}
