package io.github.mbackschat.a12.dm.adapter.capture.dto;

/**
 * One runner execution's explicit status (proposal §3). Successful EMPTY semantic output is data inside
 * {@link #SUCCESS} — never the encoding of a failed run; qualification policies fail closed when a
 * required runner is unavailable.
 */
public enum RunStatus {

    SUCCESS("success"),

    /** The runner declaredly cannot evaluate a construct in this case (named reason; never silent). */
    UNSUPPORTED("unsupported"),

    /** The per-run wall-clock budget elapsed before the runner returned. */
    TIMEOUT("timeout"),

    /** The runner threw — packet detail stays sanitized (no kernel class names, no stack traces). */
    TECHNICAL_FAILURE("technicalFailure");

    private final String wire;

    RunStatus(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }
}
