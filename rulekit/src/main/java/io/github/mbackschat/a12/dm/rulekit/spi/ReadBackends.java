package io.github.mbackschat.a12.dm.rulekit.spi;

import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

import io.github.mbackschat.a12.dm.rulekit.spi.ReadBackendResolution.None;
import io.github.mbackschat.a12.dm.rulekit.spi.ReadBackendResolution.Several;
import io.github.mbackschat.a12.dm.rulekit.spi.ReadBackendResolution.Sole;

/**
 * The resolution point for the rulekit read-back port {@link ConditionReadBack} — the peer of
 * {@code dm.core.Backends} for the port that lives one layer up (in {@code rulekit}, because it speaks
 * rulekit's AST, so {@code dm.core.Backends} cannot name it). {@link ServiceLoader} discovery + an explicit
 * {@link #prefer} for a classpath carrying more than one, cached like the core resolver.
 *
 * <p>{@link #current()} is the query and is <b>total</b>: it reports what is there, including the ambiguous
 * case, and throws for none of them. {@link #require()} is the fail-fast entry: it needs exactly one provider
 * and throws for both states it cannot serve — {@link BackendRequiredException} when none is registered (§2c —
 * read-back has no honest degraded answer) and {@link AmbiguousBackendException} when several are.
 */
public final class ReadBackends {

    private static volatile ReadBackendResolution override;
    private static volatile boolean overridden;
    private static volatile List<ConditionReadBack> discovered;

    private ReadBackends() {
    }

    /** What is registered for the port: no provider, exactly one, or several with no basis for choosing. */
    public static ReadBackendResolution current() {
        if (overridden) {
            return override;
        }
        return classify(discovered());
    }

    /** The read-back backend. Fails fast, naming what stands in the way and what to do about it. */
    public static ConditionReadBack require() {
        return requireFrom(current());
    }

    /** What discovery found, as a state. Total — every input maps to one and nothing throws. */
    static ReadBackendResolution classify(List<ConditionReadBack> found) {
        return switch (found.size()) {
            case 0 -> new None();
            case 1 -> new Sole(found.get(0));
            default -> new Several(found);
        };
    }

    /**
     * The policy half, kept separate from classification so every outcome is reachable in a test without a
     * second provider jar on the classpath: one says what is there, the other what {@code require} does
     * about it.
     */
    static ConditionReadBack requireFrom(ReadBackendResolution resolution) {
        return switch (resolution) {
            case Sole(var provider) -> provider;
            case None ignored -> throw new BackendRequiredException(
                    "condition read-back requires a provider, but none is on the classpath — read-back "
                            + "parses a stored condition into a typed AST, which has no structural-only "
                            + "fallback. Add one to read conditions back: the kernel-backed :adapter (the CLI "
                            + "bundles it), or the kernel-free :interpreter-bridge; typed authoring and model "
                            + "reads work without either.");
            case Several(var providers) -> throw new AmbiguousBackendException(providers);
        };
    }

    /**
     * Choose the provider to use, for a classpath that carries more than one. Filters what discovery already
     * instantiated rather than loading a class by name, so nothing here is reflective — which is what keeps it
     * usable from a native image.
     *
     * @throws BackendRequiredException if no registered provider is of that type; the message names what is.
     */
    public static void prefer(Class<? extends ConditionReadBack> type) {
        List<ConditionReadBack> found = discovered();
        ConditionReadBack chosen = found.stream().filter(type::isInstance).findFirst().orElseThrow(
                () -> new BackendRequiredException("no registered ConditionReadBack provider is a "
                        + type.getName() + " — registered: " + classNames(found)));
        override = new Sole(chosen);
        overridden = true;
    }

    /** Inject a read-back backend for a test ({@code null} simulates its absence). Wins over discovery until {@link #reset}. */
    public static void override(ConditionReadBack impl) {
        override = impl == null ? new None() : new Sole(impl);
        overridden = true;
    }

    /** Clear any {@link #override} or {@link #prefer} choice, and the discovery cache. */
    public static void reset() {
        override = null;
        overridden = false;
        discovered = null;
    }

    /**
     * The registered providers, instantiated once. Cached because {@link ServiceLoader#load} builds a fresh
     * set on every call, so re-running it would hand {@link #prefer} a different instance than the one a
     * subsequent {@link #require} returns.
     */
    private static List<ConditionReadBack> discovered() {
        List<ConditionReadBack> found = discovered;
        if (found == null) {
            found = new ArrayList<>();
            ServiceLoader.load(ConditionReadBack.class).forEach(found::add);
            discovered = List.copyOf(found);
            found = discovered;
        }
        return found;
    }

    static String classNames(List<? extends ConditionReadBack> providers) {
        return providers.stream().map(p -> p.getClass().getName()).sorted().toList().toString();
    }
}
