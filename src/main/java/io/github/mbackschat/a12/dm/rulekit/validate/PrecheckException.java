package io.github.mbackschat.a12.dm.rulekit.validate;

/**
 * A build-time pre-check failure that carries its {@link Diagnostic} (the PRECHECK origin of the
 * F4a three-origin model). It stays an {@link IllegalStateException} so the typed builders keep
 * their fail-fast contract — illegal states remain unrepresentable — while a caller that wants the
 * structured shape can catch this and read {@link #diagnostic()} (same shape as KERNEL/LINT
 * diagnostics, JSON-serializable), instead of scraping the message.
 */
public class PrecheckException extends IllegalStateException {

    private final transient Diagnostic diagnostic;

    public PrecheckException(Diagnostic diagnostic) {
        super(diagnostic.summary());
        this.diagnostic = diagnostic;
    }

    /** The structured PRECHECK diagnostic for this failure. */
    public Diagnostic diagnostic() {
        return diagnostic;
    }
}
