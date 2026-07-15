package io.github.mbackschat.a12.dm.adapter.capture.packet;

import java.util.Optional;

/**
 * The CLOSED role vocabulary of a receipt inventory ({@code capture-receipt-v1}) — every artifact in a
 * packet or report directory classifies to exactly one role by its relative path; an unclassifiable file
 * fails the receipt build (the inventory is closed, so stray output cannot ride along unnoticed).
 */
public enum ArtifactRole {

    PACKET_ENVELOPE("packet-envelope"),
    COPIED_REQUEST("copied-request"),
    COPIED_INPUT("copied-input"),
    LEGALITY("legality"),
    OBSERVATION("observation"),
    QUALIFICATION_PROFILE("qualification-profile"),
    QUALIFICATION_REPORT("qualification-report"),
    DIFF_REPORT("diff-report");

    private final String wire;

    ArtifactRole(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    public static Optional<ArtifactRole> ofWire(String wire) {
        for (ArtifactRole role : values()) {
            if (role.wire.equals(wire)) {
                return Optional.of(role);
            }
        }
        return Optional.empty();
    }

    /** Classify a receipt-relative path (forward slashes), or empty when no role matches. */
    public static Optional<ArtifactRole> classify(String relativePath) {
        if (relativePath.equals("PACKET.json")) {
            return Optional.of(PACKET_ENVELOPE);
        }
        if (relativePath.equals("request/scenarios.json")) {
            return Optional.of(COPIED_REQUEST);
        }
        if (relativePath.startsWith("inputs/") && relativePath.endsWith(".json")) {
            return Optional.of(COPIED_INPUT);
        }
        if (relativePath.startsWith("legality/") && relativePath.endsWith(".json")) {
            return Optional.of(LEGALITY);
        }
        if (relativePath.startsWith("observations/") && relativePath.endsWith(".json")) {
            return Optional.of(OBSERVATION);
        }
        if (relativePath.equals("PROFILE.json")) {
            return Optional.of(QUALIFICATION_PROFILE);
        }
        if (relativePath.equals("REPORT.json")) {
            return Optional.of(QUALIFICATION_REPORT);
        }
        if (relativePath.equals("DIFF.json")) {
            return Optional.of(DIFF_REPORT);
        }
        return Optional.empty();
    }
}
