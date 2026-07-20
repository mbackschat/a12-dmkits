package io.github.mbackschat.a12.dm.interpreter

import kotlin.test.Test
import kotlin.test.assertTrue

/** The JS power implementation keeps bounded working precision instead of materializing the exact base^1000. */
class DecimalPowerGrowthTest {

    @Test fun legalMaximumExponentKeepsIntermediateCoefficientBounded() {
        resetDecimalPowerPeakDigits()
        dec("9.876543210123456789012345678901234567890123456789").pow(1000)
        assertTrue(
            decimalPowerPeakDigits() <= 55,
            "MathContext(50) power should retain only 50 + exponent-digit guard precision",
        )
    }
}
