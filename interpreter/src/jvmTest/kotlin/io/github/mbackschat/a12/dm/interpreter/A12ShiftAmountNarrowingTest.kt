package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.plainDecimalAsJavaInt
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * SPEC-2026-07-27-04 — a Date/DateTime `Add*` amount is converted through Java `BigDecimal.intValue()`:
 * truncate toward zero, then retain the low signed 32 bits. This is the oracle test for the kernel-free
 * lexical reproduction: `java.math.BigDecimal.intValue()` **itself** is the comparand, so the equivalence is
 * measured, not reasoned (the discipline `A12NumberPlainFormTest` established for the plain form).
 *
 * JVM-only on purpose — `commonMain` may not import `java.*`, so the oracle can only run here; the behaviour
 * it locks is pure string arithmetic and therefore platform-independent. `DateShiftAmountTest` exercises the
 * same conversion through the evaluator on JVM **and** Kotlin/JS.
 */
class A12ShiftAmountNarrowingTest {

    private val amounts = listOf(
        // ordinary in-range amounts, both signs
        "0", "1", "-1", "7", "-7", "365",
        // fractional: TRUNCATED toward zero, never rounded
        "1.9", "-1.9", "0.9", "-0.9", "1.5", "-1.5", "0.0", "-0.0", "2.000000001",
        // the signed-32-bit boundary and its immediate neighbours
        "2147483647", "2147483648", "2147483649", "-2147483648", "-2147483649", "-2147483650",
        // whole multiples of 2^32 narrow to zero; larger magnitudes keep wrapping
        "4294967296", "-4294967296", "4294967297", "8589934592",
        // beyond every host integer type — the kernel narrows these too
        "123456789012345678901234567890", "-123456789012345678901234567890",
        "99999999999999999999.75", "-99999999999999999999.75",
    )

    @Test
    fun everyAmountMatchesBigDecimalIntValue() {
        val mismatches = amounts.mapNotNull { literal ->
            val plain = BigDecimal(literal).toPlainString()
            val expected = BigDecimal(literal).toInt()
            val actual = plainDecimalAsJavaInt(plain)
            if (actual == expected) null else "$literal -> expected $expected but was $actual"
        }
        assertEquals(emptyList(), mismatches, "narrowing diverged from BigDecimal.intValue()")
    }

    @Test
    fun theSameNarrowingRunsOffTheDecimalTheEvaluatorRenders() {
        // The evaluator feeds `Dec.display()` (a plain, trailing-zero-stripped form) to the conversion, so the
        // oracle must hold on THAT rendering too — not only on a hand-written plain literal.
        val mismatches = amounts.mapNotNull { literal ->
            val expected = BigDecimal(literal).toInt()
            val actual = plainDecimalAsJavaInt(dec(literal).display())
            if (actual == expected) null else "$literal -> expected $expected but was $actual"
        }
        assertEquals(emptyList(), mismatches, "narrowing diverged when fed Dec.display()")
    }

    @Test
    fun aNonNumericRenderingHasNoAmount() {
        listOf("", "-", "+", "abc", "1e5", ".", "-.", "1-2").forEach {
            assertNull(plainDecimalAsJavaInt(it), "expected no amount for '$it'")
        }
    }
}
