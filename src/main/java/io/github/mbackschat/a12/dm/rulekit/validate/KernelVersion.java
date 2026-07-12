package io.github.mbackschat.a12.dm.rulekit.validate;

import java.util.Optional;

import io.github.mbackschat.a12.dm.core.Backends;
import io.github.mbackschat.a12.dm.core.Severity;
import io.github.mbackschat.a12.dm.core.spi.BackendInfo;

/**
 * Runtime kernel-version awareness (F11). rulekit's catalog is verified against a floor version (the
 * oldest supported = the current one; the floor only ever rises), while the live kernel on the
 * classpath may be <b>newer</b>. This reads the running kernel's version from its jar manifest
 * ({@link Package#getImplementationVersion()} — spike-confirmed in {@code KERNEL-FINDINGS.md}) and,
 * on a mismatch, yields a single advisory INFO {@link Diagnostic}.
 *
 * <p>The governing rule (F11): the live kernel is the sole authority on rule validity; this layer
 * only <i>informs</i>. It never raises an error and never blocks — a newer kernel is a superset for
 * authoring (rulekit binds only the public surface), so the worst case is an unmodeled operator or
 * a catalog fix/gotcha keyed to the floor. When the runtime version can't be read, it stays silent
 * (bias to no false alarm).
 *
 * <p>The running version is read through the {@link BackendInfo} port (kernel-decoupling stage 3), so this
 * class imports no kernel type. No backend ⇒ no runtime version ⇒ no skew advisory — there is nothing to
 * skew against, and the degraded-mode marker carries the "no backend" fact instead.
 */
public record KernelVersion(Optional<String> runtime, String verifiedFloor) {

    public KernelVersion {
        runtime = runtime == null ? Optional.empty() : runtime;
    }

    /** The version rulekit was verified against ({@code verifiedFloor}) vs. the kernel on the classpath. */
    public static KernelVersion current(String verifiedFloor) {
        return new KernelVersion(Optional.ofNullable(runtimeVersion()), verifiedFloor);
    }

    /** The running kernel's {@code Implementation-Version} (via the {@link BackendInfo} port), or
     *  {@code null} when no backend is present or the version can't be read. */
    public static String runtimeVersion() {
        return Backends.backendInfo().map(BackendInfo::version).orElse(null);
    }

    /** True when the running kernel is known and differs from the verified floor. */
    public boolean isSkewed() {
        return runtime.isPresent() && !runtime.get().equals(verifiedFloor);
    }

    /**
     * A single advisory INFO when the running kernel differs from the verified floor; empty when the
     * versions match or the runtime version is unreadable. Source {@link DiagnosticSource#ENVIRONMENT}
     * — it concerns the toolchain, not a rule — so it never flips a {@link ValidationResult}'s
     * {@code ok}.
     */
    public Optional<Diagnostic> advisory() {
        if (!isSkewed()) {
            return Optional.empty();
        }
        String running = runtime.orElseThrow();
        return Optional.of(new Diagnostic(
                Severity.INFO,
                DiagnosticSource.ENVIRONMENT,
                Optional.of("RK_KERNEL_VERSION_SKEW"),
                "the running kernel is " + running + " but rulekit's catalog was verified against "
                        + verifiedFloor + " — authoring stays safe (rulekit binds only the public"
                        + " surface), but operators or semantics new to this kernel may not be modeled,"
                        + " and catalog fixes/gotchas are keyed to " + verifiedFloor,
                new Diagnostic.Where("(kernel)", Optional.empty()),
                Optional.empty(),
                Optional.of("re-verify rulekit's catalog against " + running
                        + ", or pin the kernel to " + verifiedFloor + " if a difference surfaces")));
    }
}
