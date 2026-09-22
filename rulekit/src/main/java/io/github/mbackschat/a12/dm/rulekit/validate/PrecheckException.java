package io.github.mbackschat.a12.dm.rulekit.validate;

import java.util.Optional;

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

    /**
     * A not-found / bad-target failure carrying the canonical {@code RK_NO_SUCH_*} code located at
     * {@code path} — so write/edit/adapter surfaces speak the one structured vocabulary instead of a bare
     * {@link IllegalArgumentException} (DG3). {@code code}'s catalog meaning + fix ride the diagnostic.
     */
    public static PrecheckException notFound(RkCode code, String path) {
        return new PrecheckException(Diagnostic.precheck(
                Optional.of(code.code()),
                code.meaning() + " (" + path + ")",
                Diagnostic.Where.path(path),
                Optional.of(code.fix()),
                Optional.empty()));
    }

    /** The structured PRECHECK diagnostic for this failure. */
    public Diagnostic diagnostic() {
        return diagnostic;
    }
}
