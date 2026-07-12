package io.github.mbackschat.a12.dm.rulekit.spi;

/**
 * Thrown when a kernel-backend-only capability is invoked with no backend on the classpath
 * (KERNEL-DECOUPLING-PROPOSAL §2c). Unlike the consistency gate — which degrades to structural-only and
 * marks the result {@code STRUCTURAL_ONLY} — condition read-back has no honest partial answer (parsing a
 * stored condition into a typed AST is the kernel's job today), so {@link ReadBackends#require()} fails fast
 * with this, carrying a corrective message, rather than returning a degraded result.
 */
public class BackendRequiredException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public BackendRequiredException(String message) {
        super(message);
    }
}
