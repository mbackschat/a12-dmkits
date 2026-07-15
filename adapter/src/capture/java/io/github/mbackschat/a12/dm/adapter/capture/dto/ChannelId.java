package io.github.mbackschat.a12.dm.adapter.capture.dto;

import java.util.Optional;

/**
 * The six public computation-result channels of {@code compute-observation-v1} (proposal §4) — separate,
 * multiplicity-preserving, enumeration-order-recording. Capture records what each runner EXPOSED per
 * channel (with per-channel {@link Availability} and granularity); it never reconstructs one channel from
 * another, labels silence as clean no-value, or manufactures internal no-value/poison state.
 */
public enum ChannelId {

    /** Every all-clean computed instance — including a value equal to the prior document value. */
    WITHOUT_ERRORS("withoutErrors"),

    /** The independently exposed changed subset (delta vs the input document). */
    CHANGED_SUBSET("changedSubset"),

    /** Every errored computed instance: target pointer, attempted value, structured cause where exposed. */
    WITH_ERRORS("withErrors"),

    /** Every cleared field-instance pointer. */
    CLEARED("cleared"),

    /** The formal errors caused by operands during formal validation. */
    FORMAL_ERRORS_IN_OPERANDS("formalErrorsInOperands"),

    /** The exact post-application state at every probe pointer after applying the public result to a
     *  FRESH copy through the supported DocumentV2 route — absent / present-empty / present-value(kind,
     *  value) kept distinct. */
    APPLIED_STATE("appliedState");

    private final String wire;

    ChannelId(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    public static Optional<ChannelId> ofWire(String wire) {
        for (ChannelId id : values()) {
            if (id.wire.equals(wire)) {
                return Optional.of(id);
            }
        }
        return Optional.empty();
    }
}
