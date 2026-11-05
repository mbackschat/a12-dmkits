package io.github.mbackschat.a12.dm.core;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Thrown when the kernel reports {@code ERROR}-severity notifications while checking a model's
 * consistency (PROJECT-DESIGN §11, line 3 — the real-kernel ground-truth step). Carries the
 * notifications (our own {@link Notification} vocabulary) so callers can map {@code MVK_*} codes to
 * actionable advice.
 */
public class ModelConsistencyException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient List<Notification> notifications;

    public ModelConsistencyException(List<Notification> notifications) {
        super(notifications.stream()
                .map(Notification::message)
                .collect(Collectors.joining("; ", "model is not consistent: ", "")));
        this.notifications = List.copyOf(notifications);
    }

    /**
     * For a model the kernel rejects during expansion, before {@code checkConsistency}.
     * Carries no notifications (the cause holds the kernel detail).
     */
    public ModelConsistencyException(String message, Throwable cause) {
        super(message, cause);
        this.notifications = List.of();
    }

    /**
     * Preserve the cause's detail for a diagnosed kernel expansion rejection.
     * The adapter calls this only for the kernel's {@code DocumentModelExpansionException}; an arbitrary
     * loading, callback, or serialization failure is not evidence that the kernel rejected the model.
     */
    public static ModelConsistencyException notKernelValid(Throwable cause) {
        String message = cause == null ? null : cause.getMessage();
        return new ModelConsistencyException(
                "the edited model is not kernel-valid" + (message != null && !message.isBlank() ? ": " + message : ""),
                cause);
    }

    /**
     * The model reader refused a model only an edit made unreadable, so the edit wrote a value the reader cannot
     * parse: the gate's rejection of that edit, not malformed input ({@link ModelLoader#checkEdit}). A model built
     * from nothing but the edit's own values is the degenerate case.
     */
    public static ModelConsistencyException editUnreadable(ModelInputException unreadable) {
        return new ModelConsistencyException("the model reader refuses a value this edit wrote: "
                + unreadable.getMessage(), unreadable);
    }

    /** The kernel notifications (ERROR severity) that caused this failure. */
    public List<Notification> notifications() {
        return notifications;
    }
}
