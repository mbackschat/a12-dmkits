package io.github.mbackschat.a12.dm.adapter.perf

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The positive controls for [PerfLaneGate] — the runtime half of the lane boundary.
 *
 * A refusal that has never been seen to fire is not a refusal, and this one is easy to get wrong in the *lenient*
 * direction: a stack walk that finds the wrong frame, or an exemption predicate that accidentally matches
 * everything, both leave the check green forever while enforcing nothing. So each case is stated as a class the
 * gate must refuse or admit, and the decision is asked of it directly.
 *
 * Every arm is exercised against a **real class of this estate** rather than an annotated fixture: [PerfGateTest]
 * for the routine tier, [PerfBenchTest] for the checkpoint tier, [EvalProfileTest] for the property-gated
 * diagnostic. Fixtures were written first and had to go — a nested `@Tag` is necessarily indented, which
 * `PerfTestClassificationTest` refuses outright, and asking the predicate about the classes it actually governs is
 * better evidence than inventing ones shaped to satisfy it.
 *
 * This class scripts class references and takes no measurement itself, which is why it needs no lane of its own.
 */
class PerfLaneGateTest {

    @Test
    fun aNeutrallyNamedUntaggedClassIsRefused() {
        val refusal = PerfLaneGate.refusalFor(NeutralHelper::class.java)

        assertNotNull(refusal, "an untagged class with no property gate must not be allowed to measure")
        assertTrue(refusal!!.contains("no performance lane")) { "the refusal must say why: $refusal" }
        assertTrue(refusal.contains("@Tag(\"perf\")")) { "and must name the corrective: $refusal" }
    }

    @Test
    fun aRoutineTierClassIsAdmitted() {
        assertNull(PerfLaneGate.refusalFor(PerfGateTest::class.java), "@Tag(\"perf\") is the routine lane")
    }

    @Test
    fun aCheckpointTierClassIsAdmitted() {
        assertNull(PerfLaneGate.refusalFor(PerfBenchTest::class.java), "@Tag(\"perf-bench\") is the checkpoint lane")
    }

    /**
     * A profiler that returns on its first line unless someone asks for it by name cannot pollute an ordinary
     * build, so it measures legitimately without a lane tag. Five classes in this estate are exactly that shape
     * (`PerfProfileTest`, `EvalProfileTest`, `ComputeOverlayProfileTest`, `PerfScalingTest`,
     * `ModelReportSinglePassTest`), which is why the exemption is a derived rule and not a list of names.
     */
    @Test
    fun aPropertyGatedDiagnosticIsAdmitted() {
        assertNull(PerfLaneGate.refusalFor(EvalProfileTest::class.java), "an opt-in diagnostic cannot run by accident")
    }

    /** The one reasoned exception must still be admitted, or the rule silently became stricter than its rationale. */
    @Test
    fun theOneAllowlistedClassIsAdmitted() {
        assertNull(
            PerfLaneGate.refusalFor(PerfGrowthEstimatorTest::class.java),
            "PerfGrowthEstimatorTest protects the CPU-clock contract and is contention-safe; it is the single " +
                "entry on the allowlist and must stay admitted",
        )
    }

    /**
     * **The end-to-end control.** The cases above ask the predicate directly; this one drives a real measuring
     * entry point from an untagged class and proves the gate is actually wired into it — a predicate nobody calls
     * would satisfy every other case here.
     */
    @Test
    fun theHarnessRefusesAMeasurementTakenFromAnUnlanedCaller() {
        val refused = assertThrows(AssertionError::class.java) {
            NeutralHelper.measureSomething()
        }

        assertTrue(refused.message!!.contains("NeutralHelper")) {
            "the refusal must name the class that tried to measure: ${refused.message}"
        }
    }

    /**
     * Neither tagged nor property-gated: what a new growth bar looks like before anyone notices.
     *
     * It builds the bar through the real seam, which is where the gate sits — refusing *measurement* was tried and
     * withdrawn, because four legitimate tests measure to drive the engines while asserting semantics. The refusal
     * fires before anything is timed, so this costs nothing.
     */
    private object NeutralHelper {
        fun measureSomething(): Double = PerfHarness.assertSubQuadraticLane(
            label = "a bar nobody put in a lane",
            small = 10,
            identityAt = { n -> PerfHarness.fingerprintedLabel("unlaned", "n=$n") },
        ) { n -> n.toDouble() }.ratio
    }
}
