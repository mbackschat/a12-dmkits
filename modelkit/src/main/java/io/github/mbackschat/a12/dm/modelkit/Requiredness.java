package io.github.mbackschat.a12.dm.modelkit;

/**
 * Whether (and how) a field must be filled — the kernel's {@code requirednessConfig.mode}
 * (KERNEL-SEMANTICS §4). {@link #OPTIONAL} emits no config at all; the two required forms differ in
 * whether the requirement is unconditional or only applies once the parent group is present.
 */
public enum Requiredness {

    /** Not required — no {@code requirednessConfig} is written. */
    OPTIONAL(null),

    /** Must be filled (unconditionally, or relative to the nearest repeating ancestor). */
    REQUIRED("absoluteOrRelativeToNextRepAncestor"),

    /** Required only when the parent group is filled ("only if the parent group is filled", §4). */
    REQUIRED_IF_PARENT_PRESENT("relativeToParent");

    private final String mode;

    Requiredness(String mode) {
        this.mode = mode;
    }

    /** The kernel {@code mode} string, or {@code null} for {@link #OPTIONAL} (emit nothing). */
    String mode() {
        return mode;
    }

    /** The inverse of {@link #mode()}: a stored {@code requirednessConfig.mode} (or {@code null}/absent) → the form. */
    static Requiredness fromMode(String mode) {
        if (mode == null || mode.isBlank()) {
            return OPTIONAL;
        }
        for (Requiredness r : values()) {
            if (mode.equals(r.mode)) {
                return r;
            }
        }
        return OPTIONAL;   // an unknown mode reads as not-required rather than guessing
    }
}
