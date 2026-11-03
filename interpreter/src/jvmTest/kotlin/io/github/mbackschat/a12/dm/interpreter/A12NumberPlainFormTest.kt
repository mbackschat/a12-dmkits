package io.github.mbackschat.a12.dm.interpreter

import java.math.BigDecimal
import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
        // negative non-zero values in each node rendering, and the plain/scientific threshold from both sides
        "-1e3", "-1.5e2", "-250.00", "-1e-7", "1e-6", "0.000001", "0.0000001",
        // a zero whose scale alone crosses the threshold
        "0.0000000", "-0e3",
        // a zero at the widest negative scale and beyond the materializing ceiling: still the one character `0`
        "0e2147483648", "-0e2147483648", "0e99999999",
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

    /**
     * A12's reader takes an unquoted number's text from its node: a `BigDecimal` for a token with a point or an
     * exponent, an integer otherwise, so the comparand is that type's own `toString`. Jackson 3's fast parser builds
     * no `BigDecimal` at the scale `Int.MIN_VALUE`, which the JDK admits, so a token there has no node text: the
     * Document is unreadable (KF564), and the oracle only holds off that one scale.
     */
    @Test
    fun everyLegalJsonNumberReadsAsItsNodesText() {
        val (unparsed, parsed) = literals.partition { BigDecimal(it).scale() == Int.MIN_VALUE }
        assertEquals(listOf("0e2147483648", "-0e2147483648"), unparsed, "the literals at the scale Jackson 3 refuses")
        unparsed.forEach { literal ->
            assertEquals(DocumentInputReasonCode.MALFORMED_JSON,
                assertFailsWith<DocumentInputError>(literal) { A12DocumentJson.numberNodeTextOrNull(literal) }
                    .diagnostics.single().reasonCode, literal)
        }
        val mismatches = parsed.mapNotNull { literal ->
            val expected = if (literal.any { it == '.' || it == 'e' || it == 'E' }) BigDecimal(literal).toString()
            else BigInteger(literal).toString()
            val actual = A12DocumentJson.numberNodeTextOrNull(literal)
            if (actual == expected) null else "$literal -> expected '$expected' but was '$actual'"
        }
        assertEquals(emptyList(), mismatches, "node text diverged from the BigDecimal / integer rendering")
        // A shift no plain form may materialize still has a short scientific rendering.
        assertEquals(BigDecimal("1e999999999").toString(), A12DocumentJson.numberNodeTextOrNull("1e999999999"))
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

    /**
     * The READ gate is A12's own converter, `new BigDecimal(String)` — a **broader** grammar than JSON's: a
     * leading `+`, a bare `.5`, a trailing `5.`, and leading zeros all parse. Same oracle, same plain form.
     */
    private val converterOnlyLiterals = listOf(
        "+5", "+250.00", "+1e5", ".5", "-.5", "+.5", "5.", "-5.", "007", "-007", "00.50", "0007e2", "+0.0",
    )

    @Test
    fun everyLiteralA12sConverterAcceptsMatchesBigDecimalToPlainString() {
        val mismatches = (literals + converterOnlyLiterals).mapNotNull { literal ->
            val expected = BigDecimal(literal).toPlainString()
            val actual = A12DocumentJson.canonicalNumberOrNull(literal)
            if (actual == expected) null else "$literal -> expected '$expected' but was '$actual'"
        }
        assertEquals(emptyList(), mismatches, "the converter form diverged from BigDecimal.toPlainString")
    }

    @Test
    fun theConverterGateRefusesExactlyWhatBigDecimalItselfRefuses() {
        listOf("", " ", "1,5", "not-a-number", "NaN", "Infinity", "0x1f", "1e", "--1", "+", "-", ".", "5..0", "1 5")
            .forEach { literal ->
                val refusedByBigDecimal = runCatching { BigDecimal(literal) }.isFailure
                assertEquals(
                    refusedByBigDecimal,
                    A12DocumentJson.canonicalNumberOrNull(literal) == null,
                    "'$literal': our gate must accept exactly what java.math.BigDecimal accepts",
                )
            }
    }

    /**
     * The converter accepts a literal exactly when its scale, fraction length minus exponent, fits 32 bits — on
     * either side, and however the exponent is spelled. Settled against the converter itself, never materializing.
     */
    @Test
    fun theConverterAcceptsExactlyTheLiteralsWhoseScaleFitsThirtyTwoBits() {
        val edges = listOf(
            "0e2147483648", "0e2147483649", "0.0e2147483649", "0.00e2147483651", "1e+2147483648", "5.e2147483648",
            ".5e2147483649", "0e-2147483647", "0e-2147483648", "0.0e-2147483647", "1.5e-2147483646", "1.5e-2147483647",
            "-1e-2147483647", "0e9223372036854775807", "0e-9223372036854775808", "0e99999999999999999999",
            "0e00000000000000000000000005",
        )
        val mismatches = edges.filter { literal ->
            runCatching { BigDecimal(literal) }.isSuccess != (A12DocumentJson.plainLengthOrNull(literal) != null)
        }
        assertEquals(emptyList(), mismatches, "our gate must accept exactly what java.math.BigDecimal accepts")
    }

    @Test
    fun theStoredLengthIsTheLengthOfBigDecimalsPlainForm() {
        val mismatches = (literals + converterOnlyLiterals + listOf("1e1000", "-1.5e-1000", "0e-1000", "-0.0e-3"))
            .mapNotNull { literal ->
                val expected = BigDecimal(literal).toPlainString().length.toLong()
                val actual = A12DocumentJson.plainLengthOrNull(literal)
                if (actual == expected) null else "$literal -> expected $expected but was $actual"
            }
        assertEquals(emptyList(), mismatches, "the stored length diverged from BigDecimal.toPlainString")
    }

    @Test
    fun anAbsurdExponentIsRefUsedRatherThanExpandedIntoAGigabyteOfDigits() {
        // A12 would expand it (`toPlainString`). The reader refuses such a value before it gets here, charging its
        // stored length against the per-field ceiling; this ceiling guards every other caller, so a 12-character
        // value cannot allocate a billion characters. The value stays verbatim and can never produce invalid JSON.
        listOf("1e999999999", "1e-999999999", "1e2147483648").forEach {
            assertNull(A12DocumentJson.canonicalNumberOrNull(it), "'$it' must not be expanded")
            assertNull(A12DocumentJson.plainNumberOrNull(it), "'$it' must not be expanded")
        }
    }

    @Test
    fun textThatIsNotAJsonNumberIsRefusedSoItCanBeWrittenAsAString() {
        // A12 keeps a value it could not convert and writes it back untouched; these must NOT become literals.
        listOf("", " ", "007", "+5", ".5", "5.", "1,5", "not-a-number", "NaN", "Infinity", "0x1f", "1e", "--1")
            .forEach { assertNull(A12DocumentJson.plainNumberOrNull(it), "'$it' must not be emitted unquoted") }
    }
}
