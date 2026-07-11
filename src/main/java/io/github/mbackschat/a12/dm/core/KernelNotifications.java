package io.github.mbackschat.a12.dm.core;

/**
 * The single kernel → dmkits notification boundary: maps the kernel's
 * {@code com.mgmtp.a12.model.notification.RankedNotification} / {@code Severity} onto our own
 * {@link Notification} / {@link Severity}. Every site that still touches the kernel and needs to surface a
 * notification through a kit's public surface (the consistency gate in {@code ModelLoader}, the
 * inject-and-check pass in {@code RuleValidator}, the adapter's per-rule {@code IProblem} read) converts
 * here, so the mapping lives in exactly one place.
 *
 * <p>This is the interim home of the seam the SPI will own: at kernel-decoupling stage 3 the conversion
 * moves into the backend ({@code ConsistencyOracle}), and this class retires with the kernel dependency.
 */
public final class KernelNotifications {

    private KernelNotifications() {
    }

    /** A kernel notification as our own {@link Notification} (severity + message — the entire consumed surface). */
    public static Notification toCore(com.mgmtp.a12.model.notification.RankedNotification notification) {
        return new Notification(toCore(notification.getSeverity()), notification.getMessage());
    }

    /** A kernel {@code Severity} as our own {@link Severity} (same three levels; exhaustive by construction). */
    public static Severity toCore(com.mgmtp.a12.model.notification.Severity severity) {
        return switch (severity) {
            case INFO -> Severity.INFO;
            case WARNING -> Severity.WARNING;
            case ERROR -> Severity.ERROR;
        };
    }
}
