package io.github.mbackschat.a12.dm.rulekit.spi;

import java.util.List;

/**
 * Thrown when read-back is required and <em>several</em> providers are registered, so which one the caller
 * wants cannot be known. Discovery order is classpath order, which is neither specified nor stable across
 * packagings, so picking one would silently bind the caller to whichever jar happened to come first — and the
 * providers do not agree: the kernel-backed one converts the whole grammar, while a partial one answers
 * {@code ReadResult.Opaque} outside its subset, which reads like a model problem rather than a wiring one.
 *
 * <p>A subclass of {@link BackendRequiredException} because it is the same requirement unmet: read-back needs
 * exactly one provider, and this says the count is wrong in the other direction. Existing handlers of the
 * supertype keep catching it.
 */
public class AmbiguousBackendException extends BackendRequiredException {

    private static final long serialVersionUID = 1L;

    public AmbiguousBackendException(List<? extends ConditionReadBack> providers) {
        super("condition read-back found " + providers.size() + " providers and cannot choose between them: "
                + ReadBackends.classNames(providers) + ". Put exactly one on the classpath, or name the one you "
                + "want with ReadBackends.prefer(<provider>.class) before reading.");
    }
}
