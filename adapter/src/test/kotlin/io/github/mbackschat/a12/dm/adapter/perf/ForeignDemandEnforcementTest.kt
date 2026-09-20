package io.github.mbackschat.a12.dm.adapter.perf

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The third precondition on an absolute-anchor bar: the machine has to have been ours while the observation ran.
 *
 * Both directions are asserted against the 2026-09-20 measurement rather than against round numbers, because a
 * floor is only as good as the healthy captures it admits. The arms: four busy spinners on eight cores produced
 * foreign demand 0.851–0.896 and must downgrade the bar; the unloaded arms produced 0.080–0.591 and must not.
 */
class ForeignDemandEnforcementTest {

    private companion object {
        /** The highest foreign demand an UNLOADED arm produced across two control arms, 24 windows each. */
        const val MEASURED_QUIET_WORST = 0.591

        /** The lowest foreign demand the four-spinner arm produced — the contamination that must downgrade. */
        const val MEASURED_LOADED_BEST = 0.851
    }

    @Test
    fun aContendedCaptureIsFlaggedRatherThanGated() {
        assertEquals(
            TimingEnforcement.FLAGGED,
            TimingEnforcement.forAbsoluteAnchor(
                isolatedLane = true, sharedMachine = false, foreignDemand = MEASURED_LOADED_BEST,
            ),
            "an absolute time taken while another process held most of the machine is the machine's number",
        )
    }

    @Test
    fun theWorstMeasuredQUIETCaptureIsStillGated() {
        assertEquals(
            TimingEnforcement.GATED,
            TimingEnforcement.forAbsoluteAnchor(
                isolatedLane = true, sharedMachine = false, foreignDemand = MEASURED_QUIET_WORST,
            ),
            "a floor that refuses the worst healthy capture disarms the anchor gate on an honest run",
        )
    }

    /**
     * The vacuity control: a floor outside the measured gap would satisfy one direction while refusing nothing.
     *
     * A floor at or below the quiet worst flags every capture; one at or above the loaded best flags none. Only a
     * floor strictly inside the gap does the job, so the constant is asserted to sit there rather than merely to
     * equal today's value — this survives a re-derivation on a quieter machine, which is the documented procedure.
     */
    @Test
    fun theFloorSitsStrictlyInsideTheMeasuredGap() {
        assertTrue(
            PerfMachineDemand.MAX_FOREIGN_CPU_UTILISATION > MEASURED_QUIET_WORST,
            "floor ${PerfMachineDemand.MAX_FOREIGN_CPU_UTILISATION} would flag a measured healthy capture",
        )
        assertTrue(
            PerfMachineDemand.MAX_FOREIGN_CPU_UTILISATION < MEASURED_LOADED_BEST,
            "floor ${PerfMachineDemand.MAX_FOREIGN_CPU_UTILISATION} would pass a measured contaminated capture",
        )
    }

    /**
     * An absent reading leaves the bar GATED, which is the direction the measurement supports.
     *
     * The sampler's failures cluster on a quiet machine — 0 of 24 windows unusable under four spinners against 4,
     * 2 and 9 of 24 on the unloaded and starved arms — so absence is weak evidence FOR a quiet box. Downgrading on
     * it would switch the anchor gate off exactly where the instrument works.
     */
    @Test
    fun anAbsentReadingDoesNotDowngradeTheBar() {
        assertEquals(
            TimingEnforcement.GATED,
            TimingEnforcement.forAbsoluteAnchor(isolatedLane = true, sharedMachine = false, foreignDemand = null),
        )
    }

    /** The other two preconditions still dominate: a missing lane cannot be rescued by a quiet machine. */
    @Test
    fun aQuietMachineDoesNotRescueAMissingIsolatedLane() {
        assertEquals(
            TimingEnforcement.UNJUDGED,
            TimingEnforcement.forAbsoluteAnchor(isolatedLane = false, sharedMachine = false, foreignDemand = 0.0),
        )
    }
}
