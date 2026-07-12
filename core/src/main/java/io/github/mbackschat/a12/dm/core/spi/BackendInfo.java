package io.github.mbackschat.a12.dm.core.spi;

/**
 * The F11 skew-read seam (KERNEL-DECOUPLING-PROPOSAL §2a): reports the running kernel backend's identity so
 * {@code KernelVersion} can advise on catalog/kernel skew without importing a kernel type. The one backend
 * ({@code :adapter}) implements it and registers via {@link java.util.ServiceLoader}; the kits resolve it
 * through {@link io.github.mbackschat.a12.dm.core.Backends}.
 *
 * <p>Absent backend ⇒ no {@code BackendInfo} ⇒ no skew advisory (there is nothing to skew against) — the
 * degraded-mode marker carries the "no backend" fact instead.
 */
public interface BackendInfo {

    /** The running kernel's {@code Implementation-Version} (read from its jar manifest), or {@code null} if unreadable. */
    String version();

    /** A short human-readable backend name (e.g. {@code "a12-kernel"}). */
    String name();
}
