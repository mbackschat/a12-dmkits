package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.model.printedNumberBound
import java.util.Locale
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A fixed-places Number bound prints as Java's `%.nf` prints its double (KERNEL-FINDINGS KF564), and
 * [printedNumberBound] rounds the double's shortest decimal half up instead. On the pinned JDK the two agree:
 * `%.nf` takes its digits from the shortest decimal too. The discriminating case is `2.82879384806159008E17`, for
 * which the older binary-to-decimal conversion emits an eighteenth digit, so a printer built on it would end in
 * `008` where the shortest decimal ends in `000`.
 *
 * JVM-only because the oracle is the JDK's own `Formatter`; the rounding it checks is common code.
 */
class PrintedNumberBoundFormatTest {

    private fun javaFormat(double: Double, places: Int) = String.format(Locale.ROOT, "%.${places}f", double)
        .let { if (it.startsWith("-") && it.trimStart('-').all { c -> c == '0' || c == '.' }) it.substring(1) else it }

    @Test
    fun theOlderConversionsExtraDigitIsNotPrinted() {
        assertEquals("282879384806159000", javaFormat(2.82879384806159008E17, 0))
        assertEquals("282879384806159000", printedNumberBound("282879384806159008", 0, 0))
    }

    @Test
    fun everySampledBoundPrintsAsJavasFormatter() {
        val random = Random(20261021)
        val mismatches = mutableListOf<String>()
        repeat(40_000) { index ->
            val double = when (index % 3) {
                0 -> Double.fromBits(random.nextLong() and Long.MAX_VALUE).takeIf { it.isFinite() && it in 1e-12..1e24 }
                1 -> random.nextLong(-100_000_000_000, 100_000_000_000) / 10.0.pow(random.nextInt(12))
                else -> random.nextDouble() * 10.0.pow(random.nextInt(-10, 24))
            } ?: return@repeat
            val places = random.nextInt(0, 13)
            val expected = javaFormat(double, places)
            val actual = printedNumberBound(double.toString(), places, places)
            if (actual != expected && mismatches.size < 10) mismatches += "$double at $places: Java $expected, ours $actual"
        }
        assertEquals(emptyList(), mismatches)
    }

    private fun Double.pow(exponent: Int) = Math.pow(this, exponent.toDouble())
}
