package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.gen.ValidValues
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** JVM+Node lock for NUMBER seeding across the declared fractional-digit interval. */
class ValidValuesFractionDigitsTest {

    @Test
    fun fixedScaleUnboundedNumberAlwaysCarriesTheMinimumDigits() {
        val field = number(scale = 2, minFractionalDigits = 2)

        val values = sample(field)

        assertTrue(values.all { fractionalDigits(it) == 2 }, "fixed-scale samples: $values")
    }

    @Test
    fun variableScaleUnboundedNumberStaysInsideTheDeclaredInterval() {
        val field = number(scale = 3, minFractionalDigits = 1)

        val widths = sample(field).map(::fractionalDigits).toSet()

        assertEquals(setOf(1, 2, 3), widths)
    }

    @Test
    fun boundValuesKeepTheirNumericValueAndPadToTheMinimumDigits() {
        val minOnly = FieldDef(
            path = "/M/Min", kind = Kind.NUMBER, scale = 2, minValue = "5", minFractionalDigits = 2,
        )
        val maxOnly = FieldDef(
            path = "/M/Max", kind = Kind.NUMBER, scale = 2, maxValue = "-5.5", minFractionalDigits = 2,
        )
        val both = FieldDef(
            path = "/M/Both", kind = Kind.NUMBER, scale = 3, minValue = "1.2", maxValue = "3.456",
            minFractionalDigits = 2,
        )

        assertTrue(sample(minOnly).all { it == "5.00" })
        assertTrue(sample(maxOnly).all { it == "-5.50" })
        assertEquals(setOf("1.20", "3.456"), sample(both).toSet())
    }

    @Test
    fun minimumAboveMaximumFailsLoudly() {
        val field = number(scale = 2, minFractionalDigits = 3)

        val error = assertFailsWith<IllegalArgumentException> {
            ValidValues.value(field, listOf(1), Random(0))
        }

        assertTrue(error.message.orEmpty().contains("minFractionalDigits 3"))
        assertTrue(error.message.orEmpty().contains("maxFractionalDigits 2"))
    }

    @Test
    fun zeroMinimumPreservesTheLegacyRandomSequence() {
        val field = number(scale = 3, minFractionalDigits = 0)

        (0L until 64L).forEach { seed ->
            assertEquals(legacyNumber(seed, scale = 3, signed = true), value(field, seed), "seed $seed")
        }
    }

    private fun sample(field: FieldDef): List<String> = (0L until 128L).map { value(field, it) }

    private fun value(field: FieldDef, seed: Long): String =
        checkNotNull(ValidValues.value(field, listOf(1), Random(seed)))

    private fun fractionalDigits(value: String): Int = value.substringAfter('.', "").length

    private fun legacyNumber(seed: Long, scale: Int, signed: Boolean): String {
        val rnd = Random(seed)
        val whole = rnd.nextInt(0, 1000)
        val fraction = if (scale > 0 && rnd.nextBoolean()) {
            "." + (1..(1 + rnd.nextInt(scale))).joinToString("") { rnd.nextInt(0, 10).toString() }
        } else {
            ""
        }
        val magnitude = "$whole$fraction"
        val sign = if (signed && rnd.nextBoolean() && magnitude.any { it in '1'..'9' }) "-" else ""
        return "$sign$magnitude"
    }

    private fun number(scale: Int, minFractionalDigits: Int): FieldDef = FieldDef(
        path = "/M/N",
        kind = Kind.NUMBER,
        scale = scale,
        minFractionalDigits = minFractionalDigits,
    )
}
