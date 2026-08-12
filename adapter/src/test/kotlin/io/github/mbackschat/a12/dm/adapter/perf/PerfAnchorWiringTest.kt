package io.github.mbackschat.a12.dm.adapter.perf

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * **The join between the machine rule and the committed block** — the step that decides whether a run is held
 * against a number at all.
 *
 * Its two ends were already checked and the wire between them was not: [PerfMachineIdentityTest] scripts
 * [MachineIdentity.check], [PerfAnchorResourceTest] holds the block's `machine` field, and nothing exercised "the
 * block names machine M, this run is on N, therefore do not assert". That is the parsed-but-never-consumed shape the
 * governing rule names — deleting the two lines that consume the decision left the whole estate green, because the
 * machine this repository is developed on happens to match. A cold review found it that way.
 */
class PerfAnchorWiringTest {

    private val here = MachineIdentity("Macmini9,1 Apple M1", processors = 8)
    private val elsewhere = MachineIdentity("MacBookAir10,1 Apple M1", processors = 8)
    private val lane = "B1 sub-quadratic check|diagnostic/B1@1 fingerprint=abc|n=5000|interpreter|cpu"

    private fun resource(machine: String?, processors: Int? = 8, environment: String = "darwin-aarch64") =
        PerfObservations.MAPPER.readTree(
            """
            {"environments": {"$environment": {
                ${machine?.let { """"machine": "$it",""" } ?: ""}
                ${processors?.let { """"processors": $it,""" } ?: ""}
                "observations": {"$lane": 8.64}
            }}}
            """.trimIndent(),
        )

    private val notes = mutableListOf<String>()

    private fun observations(
        machine: String?,
        current: MachineIdentity = here,
        enforcement: TimingEnforcement = TimingEnforcement.GATED,
        processors: Int? = 8,
        environment: String = "darwin-aarch64",
    ) = PerfAnchors.observationsFor(
        resource(machine, processors, environment), environment, current, enforcement,
    ) { notes += it }

    @Test
    fun aBlockCapturedOnThisMachineIsWhatTheRunIsHeldAgainst() {
        assertEquals(mapOf(lane to 8.64), observations(here.model))
        assertEquals(emptyList<String>(), notes, "the ordinary case must print nothing")
    }

    @Test
    fun aBlockFromAnotherMachineIsNotAssertedAgainstAndSaysSo() {
        assertNull(observations(elsewhere.model),
            "asserting a machine's absolute times against another machine's block reddens every lane on the " +
                "difference between two machines")
        assertTrue(notes.single().let { elsewhere.text in it && here.text in it }) { notes.toString() }
    }

    /** Same core count, different machine — the collision `processors` alone could not see. */
    @Test
    fun theSameCoreCountOnADifferentMachineIsStillNotAsserted() =
        assertNull(observations(elsewhere.model, processors = 8))

    @Test
    fun aBlockThatNamesNoMachineIsNotAssertedAgainst() {
        assertNull(observations(machine = null))
        assertTrue("perf-anchor.sh" in notes.single()) { notes.toString() }
    }

    /** `processors` is half the identity, so a block missing it is as unusable as one missing the model. */
    @Test
    fun aBlockThatNamesNoProcessorCountIsNotAssertedAgainst() = assertNull(observations(here.model, processors = null))

    /**
     * The hosted class, where the runner is a VM the provider picks per job: refusing would leave the nightly with
     * no absolute bar on any night the hardware differed, so it stays armed and states what it ran on.
     */
    @Test
    fun aFlaggedClassIsHeldAgainstTheBlockEvenFromAnotherMachine() {
        val observed = observations(
            elsewhere.model,
            enforcement = TimingEnforcement.FLAGGED,
            environment = "linux-amd64-ci",
        )

        assertEquals(mapOf(lane to 8.64), observed)
        assertTrue(here.text in notes.single()) { "…and it must name the machine, so a flag can be attributed" }
    }

    /** A class nobody has recorded yet: bootstrap, and no machine question to ask. */
    @Test
    fun aClassWithNoBlockAtAllYieldsNothingToAssertAgainst() {
        // The resource carries a darwin block; this run belongs to a class that is not in it at all.
        val observed = PerfAnchors.observationsFor(
            resource(here.model), "windows-amd64", here, TimingEnforcement.GATED,
        ) { notes += it }

        assertNull(observed)
        assertEquals(emptyList<String>(), notes, "bootstrap is announced by AnchorBudget, not by the machine rule")
    }
}
