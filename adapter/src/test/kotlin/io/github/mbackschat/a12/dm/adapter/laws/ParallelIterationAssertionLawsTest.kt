package io.github.mbackschat.a12.dm.adapter.laws

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * The `-ea` half of KERNEL-FINDINGS kf607: with assertions on, A12 cannot read back a message it produced, because
 * its pointer assertion refuses the `-6` its own runtime put on the references. This is why
 * [KernelProbeParallelIterationPointerTest] runs in an assertion-free task, and why the kernel probe must too.
 *
 * Runs in the default `-ea` lane on purpose; the carrier is the one that test pins.
 */
class ParallelIterationAssertionLawsTest {

    @Test
    fun readingTheMessageRaisesA12sOwnAssertion() {
        assertTrue(Class.forName(KERNEL_POINTER_IMPL).desiredAssertionStatus(), "this lane must run with -ea")
        val raised = assertThrows(AssertionError::class.java) {
            RuntimeLaws.jsonMessages(KernelProbeParallelIterationPointerTest.model,
                KernelProbeParallelIterationPointerTest.DOCUMENTS.getValue("noSlot"))
        }
        assertEquals("The repetition indexes are only expected to be >= 0 or -5", raised.message)
    }

    /** The probe catches runtime exceptions per observation; an assertion escapes the run, and no artifact is written. */
    @Test
    fun theProbeRunDiesBeforeWritingAnArtifact(@TempDir dir: Path) {
        assertThrows(AssertionError::class.java) {
            RuntimeProbeComparison.probe(dir, "ea", KernelProbeParallelIterationPointerTest.model, """["validateFull"]""",
                mapOf("noSlot" to KernelProbeParallelIterationPointerTest.DOCUMENTS.getValue("noSlot")))
        }
        assertFalse(Files.exists(dir.resolve("ea.artifact.json")))
    }

    private companion object {
        const val KERNEL_POINTER_IMPL = "com.mgmtp.a12.kernel.md.document.apiV2.PartiallyKnownDocumentMultiPointerImpl"
    }
}
