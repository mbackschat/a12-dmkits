package io.github.mbackschat.a12.dm.adapter.perf

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * **The per-pair trace, on scripted observations rather than a clock.**
 *
 * A parity row reduces six arrays to two ratios, and the reduction destroys the one thing that tells two very
 * different noise mechanisms apart. A **cold first pair** — the interpreter's per-row methods below the C2
 * threshold when the first observation is taken — drifts monotonically down the pair index; **machine
 * contention** does not order itself that way. Both leave the same spread in the reduced median, so the medians
 * cannot discriminate and only the individual observations can. This is the instrument that measured which one
 * `CO-correlation` had.
 */
class ParityTraceTest {

    @Test
    fun theTraceKeepsEveryPerPairObservationTheMedianHides() {
        val sample = PerfHarness.ParitySample(pairs = 2)
        sample.interpSmall[0] = 12.0; sample.groovySmall[0] = 20.0; sample.javaSmall[0] = 16.0
        sample.interpLarge[0] = 40.0; sample.groovyLarge[0] = 80.0; sample.javaLarge[0] = 64.0
        sample.interpSmall[1] = 8.0; sample.groovySmall[1] = 20.0; sample.javaSmall[1] = 16.0
        sample.interpLarge[1] = 30.0; sample.groovyLarge[1] = 80.0; sample.javaLarge[1] = 64.0

        assertEquals(
            listOf(
                "[perf][trace] CO pair=0 N=200 interp=12.00ms groovy=20.00ms java=16.00ms i/g=0.600 i/j=0.750",
                "[perf][trace] CO pair=0 N=800 interp=40.00ms groovy=80.00ms java=64.00ms i/g=0.500 i/j=0.625",
                "[perf][trace] CO pair=1 N=200 interp=8.00ms groovy=20.00ms java=16.00ms i/g=0.400 i/j=0.500",
                "[perf][trace] CO pair=1 N=800 interp=30.00ms groovy=80.00ms java=64.00ms i/g=0.375 i/j=0.469",
            ),
            sample.trace("CO", small = 200, large = 800),
            "every observation, labelled by pair and scale — a trace that reported only the medians would answer " +
                "the question the medians already failed to answer",
        )
    }

    @Test
    fun theTraceShowsAFallingCurveThatTheReducedRowHides() {
        val sample = PerfHarness.ParitySample(pairs = 3)
        // The interpreter's small-scale observation halves across the pairs; both kernel strategies hold steady.
        listOf(16.0, 10.0, 8.0).forEachIndexed { pair, ms ->
            sample.interpSmall[pair] = ms; sample.groovySmall[pair] = 20.0; sample.javaSmall[pair] = 16.0
            sample.interpLarge[pair] = 30.0; sample.groovyLarge[pair] = 80.0; sample.javaLarge[pair] = 64.0
        }

        // Read off the TRACE, which is the production surface — deriving the same ratios from the arrays the
        // test just wrote would assert arithmetic rather than anything the trace does.
        val smallRatios = sample.trace("CO", small = 200, large = 800)
            .filter { "N=200" in it }
            .map { it.substringAfter("i/j=") }

        assertEquals(listOf("1.000", "0.625", "0.500"), smallRatios,
            "the trace's whole point: the reduced row reports the median 0.625 and looks merely noisy, while the " +
                "observations behind it fall monotonically — the signature of a cold first pair, not contention")
        assertEquals(0.625, sample.worstInterpreterRatio(),
            "and the reduction the gate decides on is unchanged by tracing it")
    }
}
