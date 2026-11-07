package io.github.mbackschat.a12.dm.interpreter

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A12 renders one stored decimal TWICE. `A12NumberPlainFormTest` is the oracle for the serializer's rendering
 * (`toPlainString()`); this is the oracle for the **runtime's**, which kernel 31.3.1 computes as
 * `bd.setScale(min(max(bd.stripTrailingZeros().scale(), minFractionalDigits), max(0, 15 - (bd.precision() -
 * bd.scale()))), HALF_UP).toPlainString()` (`NumberConversion.determineFractionalDigits`; 31.1.1 had no cap). `BigDecimal` itself is the comparand, so the claim is measured
 * against the format's own implementation rather than derived from the formula's prose — the same discipline
 * that caught `-0e5` in the plain-form sibling. IF231.
 *
 * JVM-only because `commonMain` must stay free of `java.*`; the behaviour it locks is pure string arithmetic and
 * `A12JsonIngressDiffTest` exercises it against the real kernel.
 */
class A12RuntimeNumberFormTest {

    /** Every plain form is a `plainNumberOrNull` output — that is [A12DocumentJson.runtimeNumberForm]'s contract. */
    private val plainForms = listOf(
        // the strip direction — a fractional trailing zero beyond minFractionalDigits
        "250.00", "250.0", "250.50", "5.50", "1.10", "0.10",
        // the pad direction — fewer fractional digits than minFractionalDigits
        "250", "0", "5", "-3", "1000000",
        // no trailing zero to strip and no padding needed at some scales
        "250.5", "250.555", "0.0000001", "0.5",
        // zero in every plain spelling (BigDecimal has no negative zero, so no `-0` reaches here)
        "0.0", "0.00", "0.000",
        // negatives, incl. one that strips and one that pads
        "-3.50", "-3.5", "-0.10", "-250",
        // beyond double precision
        "1234567890123456789012.5", "1234567890123456789012.50", "0.000000000000000000001",
        // where the 15-digit cap binds: a pad past it, a rounding with and without a carry, a half that HALF_EVEN
        // would round down, negatives rounding away from zero, an integer past the cap, a deep fraction
        "123456", "12345", "1234567890.1234567", "0.1234567890123445", "9.9999999999999999", "-9.9999999999999995",
        "99999999999999999", "123456789012345678.5", "-123456789012345678.5", "0.00000000000000000012345678901234567",
    )

    private fun oracle(plain: String, minFractionalDigits: Int): String {
        val bd = BigDecimal(plain)
        val wanted = maxOf(maxOf(0, bd.stripTrailingZeros().scale()), minFractionalDigits)
        val cap = maxOf(0, 15 - (bd.precision() - bd.scale()))
        return bd.setScale(minOf(wanted, cap), RoundingMode.HALF_UP).toPlainString()
    }

    @Test
    fun everyPlainFormMatchesTheKernelsSetScaleRenderingAtEveryDeclaredMinimum() {
        val mismatches = plainForms.flatMap { plain ->
            (0..16).mapNotNull { minFractionalDigits ->
                val expected = oracle(plain, minFractionalDigits)
                val actual = A12DocumentJson.runtimeNumberForm(plain, minFractionalDigits)
                if (actual == expected) null
                else "$plain @min=$minFractionalDigits -> expected '$expected' but was '$actual'"
            }
        }
        assertEquals(emptyList(), mismatches, "the runtime rendering diverged from BigDecimal.setScale")
    }

    /**
     * The two renderings are EQUAL for most values, so a test that only compared against the oracle could pass
     * with a no-op implementation. These are the cases that must actually differ from the serializer's text —
     * the ones the whole finding is about.
     */
    @Test
    fun theRenderingsGenuinelyDivergeInBothDirections() {
        assertEquals("250", A12DocumentJson.runtimeNumberForm("250.00", 0), "strip: beyond min 0")
        assertEquals("250.0", A12DocumentJson.runtimeNumberForm("250.00", 1), "strip: down to min 1")
        assertEquals("250.00", A12DocumentJson.runtimeNumberForm("250.00", 2), "keep: exactly min 2")
        assertEquals("250.000", A12DocumentJson.runtimeNumberForm("250.00", 3), "pad: up to min 3")
        assertEquals("250.00", A12DocumentJson.runtimeNumberForm("250", 2), "pad: an integer up to min 2")
        assertEquals("250.50", A12DocumentJson.runtimeNumberForm("250.5", 2), "pad: one digit up to min 2")
        assertEquals("250.5", A12DocumentJson.runtimeNumberForm("250.50", 1), "strip while above min 1")
        assertEquals("0.00", A12DocumentJson.runtimeNumberForm("0", 2), "pad: zero up to min 2")
        assertEquals("0", A12DocumentJson.runtimeNumberForm("0.00", 0), "strip: zero down to min 0")
        // Within 15 digits a significant digit is never dropped, whatever the minimum.
        assertEquals("250.555", A12DocumentJson.runtimeNumberForm("250.555", 0), "no rounding at min 0")
        assertEquals("250.555", A12DocumentJson.runtimeNumberForm("250.555", 2), "no rounding at min 2")
        // Past them the cap binds: the pad stops at 15 digits, and a longer value rounds half up.
        assertEquals("123456.000000000", A12DocumentJson.runtimeNumberForm("123456", 10), "the pad stops at the cap")
        assertEquals("1234567890.12346", A12DocumentJson.runtimeNumberForm("1234567890.1234567", 0), "rounded to the cap")
        assertEquals("10.00000000000000", A12DocumentJson.runtimeNumberForm("9.9999999999999999", 0), "a carry")
    }
}
