package io.github.mbackschat.a12.dm.adapter.perf

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * **Which machine an absolute anchor belongs to** — the discriminator, and what a mismatch does.
 *
 * `PerfEnvironment.key` names an os/arch *family*, and absolute times are a property of the **machine**: two Apple
 * Silicon generations differ materially at the same `darwin-aarch64` key, and so do two machines of the same core
 * count. The block used to record only `processors`, which narrows the family without closing it — a real gap for as
 * long as the rule "one anchoring machine per runner class" lived in prose that nothing could check.
 *
 * **The rule is not uniform across enforcement levels, and that is deliberate.** Where a breach fails the build the
 * number decides something, so a block from another machine must not be asserted against at all. Where a breach is
 * only flagged the machine is a hosted VM nobody can pin, and refusing there would replace a possibly-noisy signal
 * with no signal — so the bar stays armed and the run says what it ran on.
 */
class PerfMachineIdentityTest {

    private val here = MachineIdentity("Macmini9,1 Apple M1", processors = 8)
    private val elsewhere = MachineIdentity("MacBookAir10,1 Apple M1", processors = 8)

    @Test
    fun theProbeNamesTheMachineThisRunIsMeasuringOn() {
        val identity = PerfEnvironment.machine

        assertTrue(identity.isKnown) {
            "the machine gate cannot see this platform: ${identity.text}. An absolute anchor is only comparable on " +
                "the machine it was captured on, so an unnamed machine silently switches the check off"
        }
        assertEquals(Runtime.getRuntime().availableProcessors(), identity.processors)
        assertEquals(identity, PerfEnvironment.machine, "the probe must be stable within one run")
    }

    @Test
    fun aBlockCapturedOnThisMachineIsCompared() {
        val decision = MachineIdentity.check(captured = here, current = here, TimingEnforcement.GATED)

        assertTrue(decision.assertAgainstBlock)
        assertNull(decision.note, "the ordinary case must not print anything")
    }

    @Test
    fun aGatedClassDoesNotAssertAgainstAnotherMachinesBlock() {
        val decision = MachineIdentity.check(captured = elsewhere, current = here, TimingEnforcement.GATED)

        assertFalse(decision.assertAgainstBlock,
            "a block from another machine would red 18 lanes on the difference between two machines")
        assertTrue(elsewhere.text in decision.note.orEmpty() && here.text in decision.note.orEmpty()) {
            "the note must name both machines, or the bootstrap looks like a missing file: ${decision.note}"
        }
    }

    /** Same core count, different machine — exactly the collision `processors` alone could not see. */
    @Test
    fun theSameCoreCountOnADifferentMachineIsStillADifferentMachine() {
        assertEquals(here.processors, elsewhere.processors)
        assertFalse(MachineIdentity.check(elsewhere, here, TimingEnforcement.GATED).assertAgainstBlock)
    }

    @Test
    fun aGatedClassDoesNotAssertAgainstABlockThatDoesNotNameItsMachine() {
        val decision = MachineIdentity.check(captured = null, current = here, TimingEnforcement.GATED)

        assertFalse(decision.assertAgainstBlock,
            "a block predating the field could have been captured anywhere, so trusting it restores the gap")
        assertTrue("perf-anchor.sh" in decision.note.orEmpty()) {
            "the note must name the way out: ${decision.note}"
        }
    }

    /** A run whose own machine cannot be probed cannot claim to be the anchoring one either. */
    @Test
    fun aGatedClassDoesNotAssertWhenItCannotNameItsOwnMachine() {
        val unknown = MachineIdentity(MachineIdentity.UNKNOWN_MODEL, processors = 8)

        val decision = MachineIdentity.check(captured = here, current = unknown, TimingEnforcement.GATED)

        assertFalse(decision.assertAgainstBlock)
        assertTrue(decision.note.orEmpty().isNotEmpty())
    }

    /**
     * The hosted class. Its machine is a VM on a shared host and is not ours to pin, so the anchor there is a bar
     * over the class rather than over one machine — which is exactly why a breach on it is FLAGGED and never fails
     * (`TimingEnforcement`). Refusing to compare would take away the only absolute signal the nightly has.
     */
    @Test
    fun aFlaggedClassStaysArmedOnAMachineItCannotPin() {
        for (captured in listOf(elsewhere, null)) {
            val decision = MachineIdentity.check(captured, current = here, TimingEnforcement.FLAGGED)

            assertTrue(decision.assertAgainstBlock,
                "a flagged bar must not be switched off by hardware nobody chose: captured=$captured")
            assertTrue(here.text in decision.note.orEmpty()) {
                "…but the run must say which machine produced the number, so a flag can be attributed: " +
                    decision.note
            }
        }
    }

    /** Nothing is asserted outside the isolated lane, so there is nothing for the machine to qualify. */
    @Test
    fun anUnjudgedLaneNeitherComparesNorComplains() {
        val decision = MachineIdentity.check(captured = elsewhere, current = here, TimingEnforcement.UNJUDGED)

        assertFalse(decision.assertAgainstBlock)
        assertNull(decision.note)
    }

    @Test
    fun theIdentityRendersItsModelAndCoreCount() {
        assertEquals("Macmini9,1 Apple M1 ×8", here.text)
    }
}
