package io.github.mbackschat.a12.dm.adapter;
import io.github.mbackschat.a12.dm.rulekit.read.ReadResult;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Diagnosability of a whole-DM read that fails with an <b>unexpected</b> exception (anything the converter
 * throws that is not an {@link UnsupportedConstructException}). The read stays tolerant — such a rule
 * degrades to a verbatim {@link ReadResult.Opaque} — but its {@code reason} must be HONEST and LOCATED:
 * a converter bug on a rule the kernel accepts is reported with its exception class + crash site, never the
 * old blanket {@code "unresolved reference (NullPointerException): null"} that hid both the class and the
 * locus and mislabeled the bug as a reference problem.
 *
 * <p>Real-world trigger: a large real-world model corpus read produced 65 such null-message NPEs, all reported identically
 * and undiagnosable from the report. The genuine "reference rulekit's content index legitimately lacks"
 * degrade keeps its {@code "unresolved reference …"} wording — see {@link UnresolvedReferenceReadTest}.
 */
class OpaqueFailureReasonTest {

    @Test
    void aCrashIsReportedWithItsClassAndLocus_notAsAnUnresolvedReference() {
        RuntimeException npe = new NullPointerException();   // a null-message NPE, like the 65 in the corpus

        String reason = KernelAdapter.opaqueFailureReason(npe);

        assertTrue(reason.startsWith("converter error (NullPointerException)"),
                () -> "names the exception class: " + reason);
        assertTrue(reason.contains(" at ") && reason.contains(getClass().getSimpleName()),
                () -> "carries the crash locus (a frame in our own code): " + reason);
        assertFalse(reason.contains("unresolved reference"),
                () -> "a raw NPE is a converter bug, not an unresolved reference: " + reason);
        assertFalse(reason.endsWith(": null"),
                () -> "never the useless '(NullPointerException): null': " + reason);
    }

    @Test
    void aCrashWithAMessageKeepsTheMessage() {
        // the real-world "is not a date field (kind=DATE_FRAGMENT)" / "Sum … listed more than once" IAE bugs:
        // an IllegalArgumentException whose message does NOT start with "no element at path:".
        RuntimeException iae = new IllegalArgumentException("is not a date field (kind=DATE_FRAGMENT)");

        String reason = KernelAdapter.opaqueFailureReason(iae);

        assertTrue(reason.startsWith("converter error (IllegalArgumentException): is not a date field"),
                () -> reason);
        assertFalse(reason.contains("unresolved reference"), () -> reason);
    }

    @Test
    void aGenuineUnresolvedReferenceKeepsItsWordingAndPath() {
        // ModelIndex.require's stable message — the benign content-index asymmetry, NOT a bug.
        RuntimeException unresolved = new IllegalArgumentException("no element at path: /Form/Header/Id");

        String reason = KernelAdapter.opaqueFailureReason(unresolved);

        assertTrue(reason.contains("unresolved reference"), () -> reason);
        assertTrue(reason.contains("no element at path: /Form/Header/Id"),
                () -> "preserves the path (and the corpus histogram-bucketing contract): " + reason);
    }
}
