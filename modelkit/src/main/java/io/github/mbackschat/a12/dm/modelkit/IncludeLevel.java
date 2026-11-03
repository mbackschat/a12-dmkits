package io.github.mbackschat.a12.dm.modelkit;

/**
 * How an included model is mounted into the host (the kernel's group {@code includeLevel}, A12K-3234) —
 * set in a mount group's {@code includeConfig}. Absent in DM-JSON ⇒ {@link #SINGLE_RG} (KF651).
 */
public enum IncludeLevel {

    /** The included model becomes <b>one (repeatable) group</b> under the mount — the classic include. */
    SINGLE_RG,

    /** The included model's <b>root</b> becomes the mount itself — its top-level fields land directly. */
    MODEL_ROOT
}
