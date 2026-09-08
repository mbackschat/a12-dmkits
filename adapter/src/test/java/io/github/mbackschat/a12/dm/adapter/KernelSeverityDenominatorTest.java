package io.github.mbackschat.a12.dm.adapter;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;

import io.github.mbackschat.a12.dm.core.Severity;
import org.junit.jupiter.api.Test;

/**
 * The control for the narrowing that keeps our own converter OUT of the adapter's consistency guard.
 *
 * <p><b>What the narrowing is for.</b> {@code KernelModelPreparation.notifications()} wraps
 * {@code service.checkConsistency} in a catch that reports "the kernel could not build this model". Until
 * 2026-09-07 the mapping callback ran inside that catch, so a defect of OURS — {@link KernelNotifications}'s
 * exhaustive switch meeting a kernel {@code Severity} constant it does not know, which is a
 * {@code MatchException} and therefore a {@code RuntimeException} — would have been published to the user as
 * a model rejection carrying a {@code KERNEL_CONFIRMED} receipt. That is the exact inverse of the defect the
 * guard exists to fix. The callback now only collects, and conversion happens after the guarded region.
 *
 * <p><b>Why this test rather than an injectable seam.</b> A cold review noted the narrowing had no control and
 * suggested a seam through which a failing converter could be injected. That would prove the catch no longer
 * swallows a converter failure — but it would also be the only reason the seam existed, and it would still
 * leave the underlying hazard live. This attacks the hazard instead: the switch can only fail if the kernel's
 * enum grows, so pinning the DENOMINATOR turns a runtime {@code MatchException} into a compile-and-test-time
 * failure on the next kernel bump, which is strictly earlier and needs no production code to exist for it.
 *
 * <p>Kernel 30.8.1 declares exactly three severities. If a bump adds a fourth, this fails and names the file
 * to extend — before any user meets it.
 */
class KernelSeverityDenominatorTest {

    @Test
    void everyKernelSeverityHasAMapping() {
        var kernelSeverities = com.mgmtp.a12.model.notification.Severity.values();

        assertEquals(3, kernelSeverities.length,
                () -> "the kernel's Severity enum grew to " + Arrays.toString(kernelSeverities)
                        + ". KernelNotifications.toCore switches over it exhaustively, so an unmapped constant "
                        + "is a MatchException at runtime — inside a code path that reports kernel verdicts to "
                        + "users. Extend KernelNotifications.toCore(Severity) and this denominator together.");

        for (var kernelSeverity : kernelSeverities) {
            Severity mapped = assertDoesNotThrow(() -> KernelNotifications.toCore(kernelSeverity),
                    () -> "no mapping for kernel severity " + kernelSeverity);
            assertEquals(kernelSeverity.name(), mapped.name(),
                    () -> "the two enums are meant to be the same three levels under the same names; "
                            + kernelSeverity + " mapped to " + mapped);
        }
    }
}
