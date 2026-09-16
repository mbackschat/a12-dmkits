package io.github.mbackschat.a12.dm.adapter.perf

import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The acceptance rule of [PairedAcceptance], on scripted curves rather than a clock — so the cross-engine bar the
 * nightly gate depends on is itself checked, and cheaply (the sibling of [GrowthFloorRetryTest]).
 *
 * [theLeanestEngineMayHaveTheHighestGrowthRatio] carries the 2026-08-11 nightly failure verbatim and is the
 * reason this class exists: the rule it replaced compared N/4N growth RATIOS across engines, which those exact
 * medians fail while the interpreter is ~9.6× cheaper per row than generated Java. It asserts the ratio
 * inversion explicitly, so reinstating a ratio comparison turns this test red.
 */
class PairedAcceptanceTest {

    /** The medians the nightly gate measured for `Number index compute` on 2026-08-11 (hosted x64 runner). */
    private val step = ScaleStep(small = 500, large = 2_000)
    private val interpreter = EngineCurve("interpreter", Growth(ratio = 3.94, smallValue = 3.148, largeValue = 12.396))
    private val groovy = EngineCurve("Groovy", Growth(ratio = 3.59, smallValue = 36.414, largeValue = 126.815))
    private val java = EngineCurve("Java", Growth(ratio = 3.86, smallValue = 30.460, largeValue = 119.347))

    @Test
    fun theLeanestEngineMayHaveTheHighestGrowthRatio() {
        assertTrue(interpreter.growth.ratio > groovy.growth.ratio && interpreter.growth.ratio > java.growth.ratio) {
            "the point of this case is that the interpreter's RATIO is the worst of the three; if these inputs " +
                "no longer have that shape the case has stopped discriminating"
        }
        assertTrue(interpreter.perUnitMs(step) * 9 < java.perUnitMs(step)) {
            "…while its per-unit cost is nearly an order of magnitude lower: " +
                "${interpreter.perUnitMs(step)} vs ${java.perUnitMs(step)}"
        }
        PairedAcceptance.requireInterpreterHasLowestPerUnitCost("number-index", step, interpreter, listOf(groovy, java))
    }

    /** Between Java's 0.0593 and Groovy's 0.0603 ms/row, so it loses to the FAIR baseline only. */
    @Test
    fun anInterpreterThatCostsMorePerUnitThanJavaAloneIsRefused() {
        val slow = EngineCurve("interpreter", Growth(ratio = 3.94, smallValue = 3.148, largeValue = 93.0))
        val failure = assertThrows(IllegalStateException::class.java) {
            PairedAcceptance.requireInterpreterHasLowestPerUnitCost("number-index", step, slow, listOf(groovy, java))
        }
        assertTrue("above [Java]" in failure.message.orEmpty() && "number-index" in failure.message.orEmpty()) {
            "the refusal must name the scenario and exactly the strategy it lost to: ${failure.message}"
        }
    }

    @Test
    fun losingToBothStrategiesNamesBoth() {
        val slow = EngineCurve("interpreter", Growth(ratio = 3.94, smallValue = 3.148, largeValue = 200.0))
        val failure = assertThrows(IllegalStateException::class.java) {
            PairedAcceptance.requireInterpreterHasLowestPerUnitCost("number-index", step, slow, listOf(groovy, java))
        }
        assertTrue("above [Groovy, Java]" in failure.message.orEmpty()) {
            "both strategies the interpreter lost to must be named: ${failure.message}"
        }
    }

    /**
     * The criterion above compares slopes, and the interpreter has ~10× of slope headroom here, so a QUADRATIC
     * interpreter still holds the lowest slope at these scales. That is not a hole in this rule but the reason
     * `pairedScenarioGrowth` also applies the self-referential catastrophe floor to the interpreter's own curve;
     * this case pins the boundary so the two criteria are never conflated into one.
     */
    @Test
    fun aQuadraticInterpreterKeepsTheLowestSlopeSoTheFloorIsASeparateCheck() {
        val quadratic = EngineCurve("interpreter", Growth(ratio = 16.0, smallValue = 3.148, largeValue = 50.368))
        assertTrue(quadratic.perUnitMs(step) < java.perUnitMs(step)) {
            "a 16× interpreter is still the cheapest per unit here, which is why the floor is a separate check"
        }
        PairedAcceptance.requireInterpreterHasLowestPerUnitCost("number-index", step, quadratic, listOf(groovy, java))
        assertThrows(AssertionError::class.java) {
            PerfHarness.assertGrowthBelowCatastropheFloor(
                "number-index interpreter", enforcement = TimingEnforcement.GATED,
            ) { quadratic.growth }
        }
    }

    @Test
    fun anEngineThatDidNotGrowWithTheScaleIsRefusedAsDegenerate() {
        val flat = EngineCurve("Groovy", Growth(ratio = 1.0, smallValue = 36.414, largeValue = 36.414))
        val failure = assertThrows(IllegalStateException::class.java) {
            PairedAcceptance.requireInterpreterHasLowestPerUnitCost("number-index", step, interpreter, listOf(flat, java))
        }
        assertTrue("degenerate" in failure.message.orEmpty() && "Groovy" in failure.message.orEmpty()) {
            "a flat curve must be refused as a broken measurement, not accepted as a fast engine: ${failure.message}"
        }
    }

    @Test
    fun aDegenerateInterpreterCurveIsRefusedToo() {
        val flat = EngineCurve("interpreter", Growth(ratio = 0.9, smallValue = 3.148, largeValue = 2.8))
        val failure = assertThrows(IllegalStateException::class.java) {
            PairedAcceptance.requireInterpreterHasLowestPerUnitCost("number-index", step, flat, listOf(groovy, java))
        }
        assertTrue("degenerate" in failure.message.orEmpty() && "interpreter" in failure.message.orEmpty()) {
            failure.message.orEmpty()
        }
    }

    @Test
    fun aStepWithNothingToCompareIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { ScaleStep(small = 2_000, large = 500) }
        assertThrows(IllegalArgumentException::class.java) { ScaleStep(small = 0, large = 500) }
        assertThrows(IllegalArgumentException::class.java) {
            PairedAcceptance.requireInterpreterHasLowestPerUnitCost("number-index", step, interpreter, emptyList())
        }
    }
}
