package io.github.mbackschat.a12.dm.modelkit;

import java.util.Set;
import java.util.TimeZone;

/**
 * The document-model {@code modelConfig.timeZone} contract at a JVM authoring/preparation boundary.
 *
 * <p>The four domains are intentionally explicit: a missing slot defaults to UTC; exact {@code UTC},
 * {@code GMT}, and {@code Europe/Berlin} are supported by the kernel-free interpreter; another Java
 * {@link TimeZone} id is kernel-legal but outside that evaluator set; and null, blank, or an unknown id
 * that silently collapses to GMT is kernel-invalid. This utility uses the host JDK's registry only to
 * classify authoring input. It is not a bundled timezone engine and does not expand interpreter support.
 */
public final class ModelTimeZoneContract {

    /** The distinct authoring/preparation outcomes for the configured slot. */
    public enum Domain {
        ABSENT_DEFAULT,
        INTERPRETER_SUPPORTED,
        KERNEL_LEGAL_INTERPRETER_UNSUPPORTED,
        KERNEL_INVALID
    }

    private static final Set<String> INTERPRETER_SUPPORTED_IDS =
            Set.of("UTC", "GMT", "Europe/Berlin");

    private ModelTimeZoneContract() {
    }

    /**
     * Classify the slot without conflating absence with an explicitly authored null.
     *
     * @param present whether {@code modelConfig} contains the {@code timeZone} key
     * @param value the scalar value, or {@code null} for an explicit JSON null
     */
    public static Domain classify(boolean present, String value) {
        if (!present) {
            return Domain.ABSENT_DEFAULT;
        }
        if (value == null || value.isBlank()) {
            return Domain.KERNEL_INVALID;
        }
        if (INTERPRETER_SUPPORTED_IDS.contains(value)) {
            return Domain.INTERPRETER_SUPPORTED;
        }
        return "GMT".equals(TimeZone.getTimeZone(value).getID())
                ? Domain.KERNEL_INVALID
                : Domain.KERNEL_LEGAL_INTERPRETER_UNSUPPORTED;
    }
}
