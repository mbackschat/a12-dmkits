package io.github.mbackschat.a12.dm.adapter.capture.dto;

/**
 * Per-channel and per-field availability (proposal §4): a field whose fidelity differs by runner carries
 * an explicit value. Missing transport is never rendered as an empty observation ("no cause"), inferred
 * from another runner, or counted as rich-result agreement — a required unavailable field fails the named
 * qualification closed.
 */
public enum Availability {

    AVAILABLE("available"),

    /** The runner does not expose this channel/field (e.g. the interpreter's typed values or structured causes). */
    NOT_EXPOSED_BY_RUNNER("notExposedByRunner"),

    /** The channel/field does not apply to this observation (e.g. a cause on a clean entry). */
    NOT_APPLICABLE("notApplicable");

    private final String wire;

    Availability(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }
}
