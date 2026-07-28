package io.github.mbackschat.a12.dm.interpreter

import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A12 writes every number with `JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN`, so the emitted text is
 * exactly `BigDecimal.toPlainString()`. This is the differential that proves our kernel-free, purely lexical
 * [A12DocumentJson.plainNumberOrNull] reproduces that oracle — `BigDecimal` itself is the comparand, so the
 * claim is measured rather than derived from the specification prose.
 *
 * JVM-only on purpose: `commonMain` must stay free of `java.*`, so the oracle can only run here. The behaviour
 * it locks is platform-independent (pure string arithmetic), and `DocumentTransportTest` exercises the same
 * function through the encode path on JVM **and** Kotlin/JS.
 */
class A12NumberPlainFormTest {

    private val literals = listOf(
        // scale preservation — the case that motivated the whole exercise
        "250.00", "5.50", "0.0", "0.00", "1.10",
        // plain integers and negatives
        "0", "-0", "250", "-3", "1000000",
        // scientific notation, both signs of exponent, both spellings
        "1e5", "1E+5", "1e+5", "1E5", "1.2e-3", "1.2E-3", "5e0", "5e-0",
        // exponents that cross the point in each direction
        "1.5e1", "1.5e2", "1.5e-1", "12345.6789e-4", "12345.6789e4",
        // beyond double precision — the case a Double silently destroys
        "1234567890123456789012.5", "0.000000000000000000001", "123456789012345678901234567890",
        // zero in every spelling: BigDecimal has no negative zero, and short-circuits at a negative scale
        "-0.0", "-0.00", "-0e5", "0e5", "0E-5", "0.0e10", "-0e-5",
        // large exponents
        "1e-10", "1e10", "9.99e-7", "1e-7",
    )

    @Test
    fun everyLegalJsonNumberMatchesBigDecimalToPlainString() {
        val mismatches = literals.mapNotNull { literal ->
            val expected = BigDecimal(literal).toPlainString()
            val actual = A12DocumentJson.plainNumberOrNull(literal)
            if (actual == expected) null else "$literal -> expected '$expected' but was '$actual'"
        }
        assertEquals(emptyList(), mismatches, "plain form diverged from BigDecimal.toPlainString")
    }

    @Test
    fun theEmittedPlainFormIsItsOwnFixedPointAndNeverScientific() {
        literals.forEach { literal ->
            val plain = requireNotNull(A12DocumentJson.plainNumberOrNull(literal)) { literal }
            assertEquals(plain, A12DocumentJson.plainNumberOrNull(plain), "re-plaining changed '$plain'")
            assertEquals(
                emptyList<Char>(),
                plain.filter { it == 'e' || it == 'E' }.toList(),
                "plain form of '$literal' kept scientific notation",
            )
            assertEquals(
                0,
                BigDecimal(plain).compareTo(BigDecimal(literal)),
                "plain form of '$literal' changed its numeric value",
            )
        }
    }

    @Test
    fun textThatIsNotAJsonNumberIsRefusedSoItCanBeWrittenAsAString() {
        // A12 keeps a value it could not convert and writes it back untouched; these must NOT become literals.
        listOf("", " ", "007", "+5", ".5", "5.", "1,5", "not-a-number", "NaN", "Infinity", "0x1f", "1e", "--1")
            .forEach { assertNull(A12DocumentJson.plainNumberOrNull(it), "'$it' must not be emitted unquoted") }
    }
}
