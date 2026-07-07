package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.FORMAL_RULE_PATH
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Multiplatform (JVM + JS) lock for IF67: the two distinct number-digit formal checks.
 *  - `stelligkeitZuLang` — a GENERIC parse check on EVERY number: > 15 TOTAL digits (integer + fractional,
 *    sign / separator excluded). Fires before the sign / scale / leading-zero / integer-cap checks.
 *  - `zahlZuvieleVorkommastellen` — the INTEGER-digit cap: explicit `maxIntegerDigits`, else a `trait:"amount"`
 *    field's implicit `15 − scale`. Reached only when total digits ≤ 15.
 * The kernel differential (both strategies) is `adapter.laws.FieldConstraintFormalDiffTest`.
 */
class NumberDigitFormalTest {

    private fun code(field: FieldDef, value: String): String? {
        val m = ModelDef(listOf(field), emptyList())
        val doc = m.newDocument().group("/G", 1).field(field.path, value, 1, 1)
        return DmInterpreter(m).validateFull(doc).firstOrNull { it.rulePath == FORMAL_RULE_PATH }?.code
    }

    private val plain0 = FieldDef("/G/N", Kind.NUMBER, scale = 0)
    private val plain2 = FieldDef("/G/N", Kind.NUMBER, scale = 2)
    private val amount2 = FieldDef("/G/N", Kind.NUMBER, scale = 2, amountTrait = true)
    private val maxInt3 = FieldDef("/G/N", Kind.NUMBER, scale = 0, maxIntegerDigits = 3)
    private val zero0 = FieldDef("/G/N", Kind.NUMBER, scale = 0, zeroNotAllowed = true)
    private val zeroMinFrac2 = FieldDef("/G/N", Kind.NUMBER, scale = 2, minFractionalDigits = 2, zeroNotAllowed = true)

    // ── stelligkeitZuLang: > 15 total digits, on ANY number (the class-2 K-only miss the sweep found) ──
    @Test fun plain16DigitsTooLong() = assertEquals("stelligkeitZuLang", code(plain0, "1234567890123456"), "16 digits, plain number")
    @Test fun plain15DigitsClean() = assertEquals(null, code(plain0, "123456789012345"), "15 digits → clean")
    @Test fun scale2Total16TooLong() = assertEquals("stelligkeitZuLang", code(plain2, "12345678901234.56"), "14 int + 2 frac = 16 total")
    @Test fun totalDigitsBeatLeadingZeros() = assertEquals("stelligkeitZuLang", code(plain0, "01234567890123456"), "17 digits + leading zero → total-digit wins")

    // ── zahlZuvieleVorkommastellen: the integer cap (amount 15−scale), total ≤ 15 so it is reached ──
    @Test fun amountIntegerCapFires() = assertEquals("zahlZuvieleVorkommastellen", code(amount2, "12345678901234"), "14 int > 15-2=13, total 14")
    @Test fun amountIntegerCapWithinCap() = assertEquals(null, code(amount2, "1234567890123"), "13 int = cap → clean")
    @Test fun amountTotal16HitsStelligkeitFirst() = assertEquals("stelligkeitZuLang", code(amount2, "12345678901234.56"), "16 total pre-empts the integer cap")

    // ── IG42: the number formal-error PRECEDENCE order (FormatDefinitionZahl.validiereFormat): missing-separator →
    //    wrong-decimals → integer-cap → zero-not-allowed → leading-zeros (LAST). ──
    @Test fun missingSeparatorBeatsZeroNotAllowed() = assertEquals("zahlOhneDezimalTrenner", code(zeroMinFrac2, "0"), "0 on zeroNotAllowed+minFrac → ohneDezimalTrenner, not zahlIstNull")
    @Test fun tooFewDecimalsBeatsZeroNotAllowed() = assertEquals("zahlFalscheAnzahlNachkommastellen", code(zeroMinFrac2, "0.0"), "0.0 → falscheAnzahl, not zahlIstNull")
    @Test fun decimalSeparatorBeatsLeadingZeros() = assertEquals("zahlHatDezimalTrenner", code(plain0, "07.5"), "07.5 scale-0 → hatDezimalTrenner, not fuehrendeNullen")
    @Test fun wrongDecimalsBeatsLeadingZeros() = assertEquals("zahlFalscheAnzahlNachkommastellen", code(plain2, "07.555"), "07.555 → falscheAnzahl, not fuehrendeNullen")
    @Test fun integerCapBeatsLeadingZeros() = assertEquals("zahlZuvieleVorkommastellen", code(maxInt3, "0007"), "0007 → zuvieleVorkommastellen, not fuehrendeNullen")
    @Test fun zeroNotAllowedBeatsLeadingZeros() = assertEquals("zahlIstNull", code(zero0, "00"), "00 → zahlIstNull, not fuehrendeNullen")
    // controls: each check still fires alone (single-check behavior preserved by the reorder)
    @Test fun leadingZerosStillFiresAlone() = assertEquals("zahlHatFuehrendeNullen", code(plain0, "007"), "007 (no other violation) → fuehrendeNullen")
    @Test fun zeroNotAllowedStillFiresAlone() = assertEquals("zahlIstNull", code(zero0, "0"), "0 (no minFrac) → zahlIstNull")
}
