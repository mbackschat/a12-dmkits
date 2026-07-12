package io.github.mbackschat.a12.dm.rulekit.spi;

import java.util.Optional;
import java.util.ServiceLoader;

/**
 * The resolution point for the rulekit read-back port {@link ConditionReadBack} — the peer of
 * {@code dm.core.Backends} for the port that lives one layer up (in {@code rulekit}, because it speaks
 * rulekit's AST, so {@code dm.core.Backends} cannot name it). {@link ServiceLoader} discovery + an explicit
 * {@link #override} for tests, cached like the core resolver.
 *
 * <p>{@link #require()} is the fail-fast entry: no backend ⇒ {@link BackendRequiredException} (§2c —
 * read-back has no honest degraded answer).
 */
public final class ReadBackends {

    private static volatile ConditionReadBack override;
    private static volatile boolean overridden;
    private static volatile Optional<ConditionReadBack> cache;

    private ReadBackends() {
    }

    /** The read-back backend, or empty when none is on the classpath. */
    public static Optional<ConditionReadBack> current() {
        if (overridden) {
            return Optional.ofNullable(override);
        }
        Optional<ConditionReadBack> cached = cache;
        if (cached == null) {
            cached = ServiceLoader.load(ConditionReadBack.class).findFirst();
            cache = cached;
        }
        return cached;
    }

    /** The read-back backend, or a fail-fast {@link BackendRequiredException} if none is present. */
    public static ConditionReadBack require() {
        return current().orElseThrow(() -> new BackendRequiredException(
                "condition read-back requires a kernel backend, but none is on the classpath — read-back "
                        + "parses a stored condition into a typed AST, which has no structural-only fallback. "
                        + "Add the kernel backend (dmtool bundles it; a library consumer adds the :adapter "
                        + "module) to read conditions back; typed authoring and model reads work without it."));
    }

    /** Inject a read-back backend for a test ({@code null} simulates its absence). Wins over discovery until {@link #reset}. */
    public static void override(ConditionReadBack impl) {
        override = impl;
        overridden = true;
    }

    /** Clear any {@link #override} and the discovery cache. */
    public static void reset() {
        override = null;
        overridden = false;
        cache = null;
    }
}
