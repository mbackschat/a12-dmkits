package io.github.mbackschat.a12.dm.rulekit.spi;

import java.util.Map;

import io.github.mbackschat.a12.dm.core.ModelResolver;
import io.github.mbackschat.a12.dm.rulekit.read.ReadResult;

/**
 * The R4 read seam (KERNEL-DECOUPLING-PROPOSAL §2a) — the port a <em>published</em> consumer reads a model's
 * stored rule conditions through, into rulekit's typed AST, without a kernel type. Lives in {@code rulekit}
 * because it speaks rulekit's AST ({@link ReadResult} / {@code Condition}). The kernel backend
 * ({@code :adapter}) provides the authoritative, canonical implementation and registers it via
 * {@link java.util.ServiceLoader}; the kernel-free interpreter-bridge provider (ruling 6) is the second.
 * Resolve it through {@link ReadBackends}.
 *
 * <p>Read-back has <b>no honest partial answer</b> without a backend, so it fails fast rather than degrading:
 * {@link ReadBackends#require()} needs exactly one provider and throws {@link BackendRequiredException} when
 * none is present (§2c), or {@link AmbiguousBackendException} when several are and it cannot tell which is
 * wanted. {@link ReadBackends#current()} reports all three states without throwing.
 *
 * <p>Scope: only {@link #readAll} is exposed today — the one published consumer (schemakit's Tier-A
 * projection) reads every stored rule. The port grows a method when a real consumer needs it (the CLI keeps
 * calling {@code :adapter}'s richer {@code KernelAdapter} directly — it bundles the backend).
 */
public interface ConditionReadBack {

    /**
     * Read every stored <em>validation rule</em>'s condition in {@code modelJson} into a typed
     * {@link ReadResult} (Converted or Opaque), keyed by rule full name. Includes / type-def imports resolve
     * through {@code resolver} (use {@link io.github.mbackschat.a12.dm.core.ReferenceResolvers#selfContained()}
     * for a self-contained model). Tolerant: a rule the kernel accepts never throws (an unconverted construct
     * becomes {@code Opaque}).
     */
    Map<String, ReadResult> readAll(String modelJson, ModelResolver resolver);
}
