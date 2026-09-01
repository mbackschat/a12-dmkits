package io.github.mbackschat.a12.dm.adapter.laws

import java.util.Locale
import java.util.Optional
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * **A probe artifact an external consumer will pin must not claim a revision the tree does not match.** The
 * header already stamps `sourceRevision` + `sourceState`, single-sourced from `scripts/SourceProvenance.java`,
 * so it cannot claim a revision the build did not measure — but nothing stopped it stamping `DIRTY` and handing
 * the artifact over anyway, and then the revision names a tree that never produced those rows.
 *
 * **Measured cost, not a hypothetical.** a12-kernel-lean probes this repository's kernel through
 * `:adapter:kernelProbe` and discarded a round on 2026-08-31 after its artifact came back `state: DIRTY`. Their
 * mitigation is to re-check `git status` before every probe, which leaves the window between that check and the
 * probe's own read open, and cannot be closed from their side at all: writing into this checkout is outside what
 * their instructions permit. So the fix belongs here.
 *
 * **The enforcement flag is set by the BUILD, not inferred from a missing property.** The in-process callers in
 * [KernelRuntimeProbeTest] invoke `main` directly with no provenance at all, which reads as `UNKNOWN`; treating
 * absence as "refuse" would break every one of them, and treating it as "allow" would be a loophole that grows
 * quiet. The Gradle task says explicitly that its artifact is for handing over.
 */
class KernelProbePinnedSourceTest {

    private companion object {
        const val MODEL = """{"name":"probe","locale":"en_US"}"""
    }

    /** A minimal request — only the header is under test, so the rows never run. */
    private fun request() = KernelRuntimeProbe.Request(
        "probe", MODEL, Locale.US, Optional.empty(),
        setOf(KernelRuntimeProbe.Observation.VALIDATE_FULL),
        listOf(KernelRuntimeProbe.Row("r", "{}")),
    )

    @Test
    fun aCleanTreeIsAccepted() {
        KernelRuntimeProbe.requirePinnedSource("CLEAN", true)
    }

    /**
     * Both non-clean states refuse, and `UNKNOWN` is not the lenient one: an artifact whose revision could not be
     * determined is exactly as unpinnable as one whose tree has moved, so admitting it would leave the class open
     * through the state that means "we could not tell".
     */
    @Test
    fun aDirtyOrUndeterminedTreeIsRefusedAndTheMessageSaysHowToProceed() {
        for (state in listOf("DIRTY", "UNKNOWN")) {
            val refused = assertThrows(IllegalStateException::class.java) {
                KernelRuntimeProbe.requirePinnedSource(state, true)
            }
            val message = refused.message.orEmpty()
            assertTrue(state in message, "$state — the refusal must name the state it saw: $message")
            assertTrue(
                "probeAllowUnpinned" in message,
                "$state — the refusal must name the escape, or a legitimate exploration run is stuck: $message",
            )
        }
    }

    /** The does-not-fire arm: with enforcement off, an exploration run proceeds on a dirty tree. */
    @Test
    fun theEscapeLetsAnExplorationRunProceed() {
        KernelRuntimeProbe.requirePinnedSource("DIRTY", false)
    }

    /**
     * The escape must not LAUNDER the artifact — it permits the run, it does not rewrite the stamp. Without this,
     * `-PprobeAllowUnpinned` would turn a refusal into a silently clean-looking artifact, which is strictly worse
     * than the refusal it replaces.
     */
    @Test
    fun theEscapeDoesNotChangeWhatTheHeaderStamps() {
        val previous = System.getProperty(KernelRuntimeProbe.SOURCE_STATE_PROPERTY)
        try {
            System.setProperty(KernelRuntimeProbe.SOURCE_STATE_PROPERTY, "DIRTY")
            KernelRuntimeProbe.requirePinnedSource("DIRTY", false)
            assertEquals("DIRTY", KernelRuntimeProbe.header(request()).sourceState(),
                "the escape permits the run; the artifact must still say the tree had moved")
        } finally {
            if (previous == null) {
                System.clearProperty(KernelRuntimeProbe.SOURCE_STATE_PROPERTY)
            } else {
                System.setProperty(KernelRuntimeProbe.SOURCE_STATE_PROPERTY, previous)
            }
        }
    }
}
