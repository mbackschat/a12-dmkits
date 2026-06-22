package io.github.mbackschat.a12.dm.core;

import java.util.List;
import java.util.stream.Collectors;

import com.mgmtp.a12.model.notification.RankedNotification;

/**
 * Thrown when the kernel reports {@code ERROR}-severity notifications while checking a model's
 * consistency (PROJECT-DESIGN §11, line 3 — the real-kernel ground-truth step). Carries the raw
 * notifications so callers can map {@code MVK_*} codes to actionable advice.
 */
public class ModelConsistencyException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient List<RankedNotification> notifications;

    public ModelConsistencyException(List<RankedNotification> notifications) {
        super(notifications.stream()
                .map(RankedNotification::getMessage)
                .collect(Collectors.joining("; ", "model is not consistent: ", "")));
        this.notifications = List.copyOf(notifications);
    }

    /**
     * For a model the kernel rejects <em>before</em> {@code checkConsistency} (deserialize/expand tier —
     * e.g. a duplicate element id, a malformed type node), so a structure-edit gate can surface every
     * kernel rejection as one type. Carries no ranked notifications (the cause holds the kernel detail).
     */
    public ModelConsistencyException(String message, Throwable cause) {
        super(message, cause);
        this.notifications = List.of();
    }

    /**
     * The single uniform "edited model is not kernel-valid" wrap for a deserialize/expand-tier rejection (a
     * non-{@code ModelConsistencyException} {@link RuntimeException} / {@code IOException} thrown while
     * re-binding the edited model), <b>surfacing the cause's message</b> so the rejection is never
     * content-free. This is the shared root for the two kernel-gate sites — the per-edit gate
     * ({@code ModelDocument.validate}) and the session terminal gate ({@code EditSession.validate}), which
     * backs the {@code apply}/{@code batch} commit — so the cause-append cannot drift between them. (Probe
     * 04 fixed the per-edit site inline; probe 07 found the terminal gate still bare — the duplication was
     * the defect, hence one factory.)
     */
    public static ModelConsistencyException notKernelValid(Throwable cause) {
        String message = cause == null ? null : cause.getMessage();
        return new ModelConsistencyException(
                "the edited model is not kernel-valid" + (message != null && !message.isBlank() ? ": " + message : ""),
                cause);
    }

    /** The kernel notifications (ERROR severity) that caused this failure. */
    public List<RankedNotification> notifications() {
        return notifications;
    }
}
