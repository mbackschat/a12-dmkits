package io.github.mbackschat.a12.dm.rulekit.spi;

/**
 * Thrown when a capability that needs a backend cannot be given exactly one (KERNEL-DECOUPLING-PROPOSAL
 * §2c). Unlike the consistency gate — which degrades to structural-only and marks the result
 * {@code STRUCTURAL_ONLY} — condition read-back has no honest partial answer, so {@link ReadBackends#require()}
 * fails fast with this, carrying a corrective message, rather than returning a degraded result.
 *
 * <p>This is the none-registered case; {@link AmbiguousBackendException} extends it for the other direction,
 * several registered, so a handler of this type catches both.
 */
public class BackendRequiredException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public BackendRequiredException(String message) {
        super(message);
    }
}
