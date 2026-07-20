package io.github.mbackschat.a12.dm.adapter.profile;

import java.util.Locale;
import java.util.Objects;

/**
 * Identifier-free opaque read-back families.
 *
 * <p>Raw converter reasons can contain source paths, literals, operator arguments, and implementation
 * class names. A profile records only this closed failure family and never serializes the raw reason.
 */
enum OpaqueReasonCategory {
    CONVERSION_LIMIT,
    KERNEL_EXTRACTION_FAILURE,
    READER_FAILURE;

    static OpaqueReasonCategory of(String reason) {
        String normalized = Objects.requireNonNull(reason, "reason")
                .toLowerCase(Locale.ROOT);
        if (normalized.startsWith("reader failure:")) {
            return READER_FAILURE;
        }
        if (normalized.contains("kernel could not extract")) {
            return KERNEL_EXTRACTION_FAILURE;
        }
        return CONVERSION_LIMIT;
    }
}
