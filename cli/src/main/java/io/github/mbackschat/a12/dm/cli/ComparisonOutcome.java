package io.github.mbackschat.a12.dm.cli;

/**
 * The result of comparing two artifacts of the same kind — {@code profile compare}'s verdict
 * (CLI-SPEC §3b "The result envelope").
 *
 * <p><b>Why this exists.</b> Profile equality was reported as the envelope's {@code valid}, which claims "the
 * subject model is kernel-valid". Two profiles differing says nothing about any model's validity, and no
 * consistency gate runs on this path at all — so {@code valid} was a category error that also made
 * {@code exitCode()} read a {@code JsonNode} to decide the process's exit status. A comparison is its own kind of
 * result: the outcome is typed, rides {@code data}, and owns its exit code.
 *
 * <p><b>Exit semantics are preserved exactly</b>: {@code differs} still exits 1. This is a reclassification of what
 * the result <em>is called</em>, never a change to what a caller observes — CI almost certainly branches on that
 * code.
 */
public enum ComparisonOutcome {

    /** Every compared axis agrees. */
    EQUIVALENT(true, 0),
    /** At least one axis differs — exit 1, as before the reclassification. */
    DIFFERS(false, 1);

    private final boolean matches;
    private final int exitCode;

    ComparisonOutcome(boolean matches, int exitCode) {
        this.matches = matches;
        this.exitCode = exitCode;
    }

    /** Classify from the compared axes' own verdict. */
    public static ComparisonOutcome of(boolean matches) {
        return matches ? EQUIVALENT : DIFFERS;
    }

    /** The published {@code data.matches} boolean — the key consumers already read (top level, not nested). */
    public boolean matches() {
        return matches;
    }

    /** The process exit code this outcome commands, owned here rather than derived from serialized JSON. */
    public int exitCode() {
        return exitCode;
    }

}
