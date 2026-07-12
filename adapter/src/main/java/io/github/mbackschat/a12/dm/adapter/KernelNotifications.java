package io.github.mbackschat.a12.dm.adapter;

import io.github.mbackschat.a12.dm.core.Notification;
import io.github.mbackschat.a12.dm.core.Severity;

/**
 * The kernel → dmkits notification boundary, living in the backend (kernel-decoupling stage 3): maps the
 * kernel's {@code com.mgmtp.a12.model.notification.RankedNotification} / {@code Severity} onto our own
 * {@link Notification} / {@link Severity}. It is the kernel-side of the {@code ConsistencyOracle} seam —
 * the {@link KernelConsistencyOracle} converts every {@code checkConsistency} notification here, and
 * {@code KernelAdapter.diagnose} converts the per-rule {@code IProblem}s the same way, so the mapping lives
 * in exactly one place and no kernel notification type crosses into a kit.
 */
final class KernelNotifications {

    private KernelNotifications() {
    }

    /** A kernel notification as our own {@link Notification} (severity + message — the entire consumed surface). */
    static Notification toCore(com.mgmtp.a12.model.notification.RankedNotification notification) {
        return new Notification(toCore(notification.getSeverity()), notification.getMessage());
    }

    /** A kernel {@code Severity} as our own {@link Severity} (same three levels; exhaustive by construction). */
    static Severity toCore(com.mgmtp.a12.model.notification.Severity severity) {
        return switch (severity) {
            case INFO -> Severity.INFO;
            case WARNING -> Severity.WARNING;
            case ERROR -> Severity.ERROR;
        };
    }
}
