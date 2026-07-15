package io.github.mbackschat.a12.dm.adapter.capture.dto;

/**
 * A computed value as one runner exposed it — TYPED ({@code valueV2}) and RENDERED (string) forms carried
 * as separate, separately-available fields (proposal §4), so later normalization or rendering research
 * never requires recapture. A runner that exposes only the rendered form (the interpreter) declares
 * {@code typed = notExposedByRunner}; the typed form is never derived from the rendered one.
 */
public record ObservedValue(
        Availability typedAvailability,
        EvidenceValue typed,
        Availability renderedAvailability,
        String rendered) {

    public static ObservedValue both(EvidenceValue typed, String rendered) {
        return new ObservedValue(Availability.AVAILABLE, typed, Availability.AVAILABLE, rendered);
    }

    /** The interpreter's shape: rendered form only, typed transport honestly absent. */
    public static ObservedValue renderedOnly(String rendered) {
        return new ObservedValue(Availability.NOT_EXPOSED_BY_RUNNER, null, Availability.AVAILABLE, rendered);
    }

    /** Neither form applies (e.g. an errored instance whose attempted value the runner did not surface). */
    public static ObservedValue notApplicable() {
        return new ObservedValue(Availability.NOT_APPLICABLE, null, Availability.NOT_APPLICABLE, null);
    }
}
