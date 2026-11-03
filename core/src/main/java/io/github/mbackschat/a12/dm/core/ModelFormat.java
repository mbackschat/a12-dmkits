package io.github.mbackschat.a12.dm.core;

/**
 * The document-model format the kit targets — a {@code dm.core} fact (the shared substrate), so both
 * {@code dm.modelkit} (which stamps it on new-from-scratch models) and {@code dm.rulekit} (whose
 * load-time version check compares a loaded model's {@code header.modelVersion} against it) read it from
 * here rather than reaching across the sibling boundary.
 *
 * <p>This is the model-format version the running kernel implements (the "application version" the kernel
 * reports in a {@code "Version mismatch …"} ERROR). The kernel accepts an older same-major model and
 * rejects a newer or different-major one — see {@code KERNEL-FINDINGS}. Bump this in lockstep with the
 * kernel pin (see {@code KERNEL-UPGRADE}).
 */
public final class ModelFormat {

    /** The {@code header.modelVersion} this build authors and validates against. */
    public static final String VERSION = "29.4.0";

    private ModelFormat() {
    }
}
