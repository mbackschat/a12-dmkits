package io.github.mbackschat.a12.dm.interpreter

import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A12 renders one stored decimal TWICE. `A12NumberPlainFormTest` is the oracle for the serializer's rendering
 * (`toPlainString()`); this is the oracle for the **runtime's**, which the kernel computes as
 * `bd.setScale(max(bd.stripTrailingZeros().scale(), minFractionalDigits)).toPlainString()`
 * (`FormatDefinitionZahl.convertFromBasicType`). `BigDecimal` itself is the comparand, so the claim is measured
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
    )

    private fun oracle(plain: String, minFractionalDigits: Int): String {
        val bd = BigDecimal(plain)
        return bd.setScale(maxOf(bd.stripTrailingZeros().scale(), minFractionalDigits)).toPlainString()
    }

    @Test
    fun everyPlainFormMatchesTheKernelsSetScaleRenderingAtEveryDeclaredMinimum() {
        val mismatches = plainForms.flatMap { plain ->
            (0..4).mapNotNull { minFractionalDigits ->
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
        // A significant digit is NEVER dropped, whatever the minimum — the destination scale is a floor, never a cap.
        assertEquals("250.555", A12DocumentJson.runtimeNumberForm("250.555", 0), "no rounding at min 0")
        assertEquals("250.555", A12DocumentJson.runtimeNumberForm("250.555", 2), "no rounding at min 2")
    }
}
