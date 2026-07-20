package io.github.mbackschat.a12.dm.interpreter

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * The [Dec] contract (`MathContext(50)` HALF_UP on +/−/×/÷/^, PLAIN-decimal rendering — the kernel's
 * `VkBigDecimal.INTERNAL_PRECISION`) must hold on BOTH actuals: JVM BigDecimal AND JS big.js. These vectors
 * run on both via `:interpreter:check` (jvmTest + jsNodeTest), so an actual that breaks the contract fails here.
 *
 * Before the fix this whole suite was JVM-green / JS-red on most cases, and red on BOTH for negative powers:
 *  - JS `×` delegated to big.js exact (keeping >50 significant digits, where the kernel caps at 50);
 *  - JS `÷` / negative `^` used big.js's `Big.DP` (20 DECIMAL places), not 50 SIGNIFICANT digits;
 *  - JS parsed `Big.toString()` as plain, though big.js emits exponent form past `Big.PE`(21)/`Big.NE`(−7),
 *    corrupting scale/precision accounting and computed storage/display;
 *  - the negative-power ACTUAL (both platforms) computed `1/(v^|n|)`, but the kernel computes `(1/v)^|n|`
 *    (`VkBigDecimal.power` → `getKehrwert(this).power(|n|)`) — a difference observable past scale-19 for a
 *    large-magnitude result (e.g. `0.3^-64`, 34 integer digits).
 *
 * The expected literals are JVM/Node primitive locks. Observable arithmetic discriminators are independently
 * anchored to both real-kernel strategies by `adapter.laws.DecimalPrecisionParityDiffTest`.
 */
class DecimalHostParityTest {

    @Test fun additionAndSubtractionCapAt50SignificantDigits() {
        assertEquals(
            "100000000000000000000000000000000000000000000000010",
            (dec("99999999999999999999999999999999999999999999999999") + dec("6")).display(),
            "addition rounds once under MathContext(50)",
        )
        assertEquals(
            "100000000000000000000000000000000000000000000000000",
            (dec("100000000000000000000000000000000000000000000000001") - dec("1")).display(),
            "subtraction rounds once under MathContext(50)",
        )
    }

    // ── ÷ : a non-terminating quotient is rounded to 50 SIGNIFICANT digits HALF_UP (not 20 decimal places) ────
    @Test fun divisionCapsAt50SignificantDigits() {
        assertEquals("333333.33333333333333333333333333333333333333333333", dec("1000000").div(dec("3")).display(), "1000000/3")
        assertEquals("0.33333333333333333333333333333333333333333333333333", dec("1").div(dec("3")).display(), "1/3")
        assertEquals("0.28571428571428571428571428571428571428571428571429", dec("2").div(dec("7")).display(), "2/7 (HALF_UP last digit)")
        assertEquals(
            "0.048760721392437376732357175376404888164903531794647",
            dec("17980052662979").div(dec("368740497464577")).display(),
            "division performs one HALF_UP rounding, not decimal-place rounding followed by prec(50)",
        )
        assertEquals(
            "10000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000",
            dec("1").div(dec("0.0000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000001")).display(),
            "large magnitude gaps do not consume a proportional big.js DP budget",
        )
    }

    // ── × : a product beyond 50 significant digits is CAPPED (low digits dropped), not kept exact ─────────────
    @Test fun multiplicationCapsAt50SignificantDigits() {
        val a = dec("123456789012345678901234567890")   // 30 digits
        val b = dec("987654321098765432109876543210")   // 30 digits → 60-digit exact product
        val capped = dec("121932631137021795226185032733622923332237463801110000000000")   // 50 sig + trailing zeros
        val exact = dec("121932631137021795226185032733622923332237463801111263526900")     // full 60-digit product
        assertEquals(capped, a.times(b), "30d × 30d capped to 50 significant digits")
        assertNotEquals(exact, a.times(b), "the exact 60-digit product is NOT retained")
    }

    // ── ^ positive : a power beyond 50 significant digits is capped, like × ───────────────────────────────────
    @Test fun positivePowerCapsAt50SignificantDigits() {
        // 123456789^7 exact = 57 digits → capped to 50 sig + 7 trailing zeros
        assertEquals(dec("437124189620885610010004822109262358637075660656880000000"), dec("123456789").pow(7), "123456789^7 capped")
        assertEquals(
            "0.0053340688743367123920238743454888667967326752493319",
            dec("0.473471768303411").pow(7).display(),
            "bounded working precision matches BigDecimal.pow for the first concrete discriminator",
        )
        assertEquals(
            "569020148731655962502965624918777104.60466073293586",
            dec("4.870781059298").pow(52).display(),
            "bounded working precision matches BigDecimal.pow for the scale-19-observable discriminator",
        )
        assertEquals("1", dec("0").pow(0).display(), "zero to the zero power follows BigDecimal.pow")
        assertEquals(
            "1" + "0".repeat(1000),
            dec("10").pow(1000).display(),
            "the legal exponent boundary remains supported",
        )
    }

    // ── ^ negative : kernel form (1/v)^|n|, each step at 50 sig HALF_UP (NOT 1/(v^|n|)) ───────────────────────
    @Test fun negativePowerUsesKernelReciprocalForm() {
        assertEquals("0.33333333333333333333333333333333333333333333333333", dec("3").pow(-1).display(), "3^-1")
        // (1/7)^2 ends ...162 ; the old 1/(7^2) form ended ...163 — the reciprocal-first form is the kernel's
        assertEquals("0.020408163265306122448979591836734693877551020408162", dec("7").pow(-2).display(), "7^-2 kernel form")
        assertEquals("0.125", dec("2").pow(-3).display(), "2^-3 exact (both forms agree)")
        // large magnitude: 34 integer digits, so the form difference survives the scale-19 comparison pre-round
        assertEquals("2912324058756262786433368781182555.3552912554484006", dec("0.3").pow(-64).display(), "0.3^-64 kernel form")
    }

    // ── rendering : PLAIN decimal at exponent-form extremes (big.js toString would emit 1e+25 / 1e-8) ─────────
    @Test fun displayIsPlainAtExponentExtremes() {
        assertEquals("10000000000000000000000000", dec("10000000000000000000000000").display(), "1e25 renders plain")
        assertEquals("0.00000001", dec("0.00000001").display(), "1e-8 renders plain")
        assertEquals("12345000000000000000000000", dec("12345000000000000000000000").display(), "1.2345e25 renders plain")
        assertEquals("1" + "0".repeat(1_000_000), dec("1e1000000").display(), "the inclusive big.js PE boundary renders plain")
        assertEquals("0." + "0".repeat(999_999) + "1", dec("1e-1000000").display(), "the inclusive big.js NE boundary renders plain")
    }

    // ── the stripped (precision, scale) accounting the computed-store rule (IF92) reads must survive extremes ──
    @Test fun strippedAccountingSurvivesExponentExtremes() {
        assertEquals(8, dec("0.00000001").fractionalDigits(), "1e-8 has 8 significant fractional digits")
        assertEquals(1, dec("0.00000001").strippedPrecision(), "1e-8 has 1 significant digit")
        assertEquals(-25, dec("10000000000000000000000000").fractionalDigits(), "1e25 whole value → stripped scale −25")
        assertEquals(1, dec("10000000000000000000000000").strippedPrecision(), "1e25 has 1 significant digit")
    }

    // ── computed-store rendering (display(min) / displayAtScale) stays plain at extremes ──────────────────────
    @Test fun computedStoreRenderingIsPlainAtExtremes() {
        assertEquals("10000000000000000000000000.00", dec("10000000000000000000000000").display(2), "min-fraction pad, plain")
        assertEquals("0.00000001", dec("0.00000001").display(2), "natural scale 8 > min 2 kept, plain")
        assertEquals("0.0000000100", dec("0.00000001").displayAtScale(10), "exact scale 10, plain")
    }
}
