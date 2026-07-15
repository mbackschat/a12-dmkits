package io.github.mbackschat.a12.dm.adapter.capture.dto;

import java.util.Optional;

/**
 * The named, versioned, IMMUTABLE comparison projections (proposal §5): every comparison names its two
 * runners, its projection id + version, and its included/unavailable fields — there is no global
 * {@code agrees} Boolean, so agreement at one granularity can never masquerade as agreement on attempted
 * values, causes, or application shape.
 */
public enum ProjectionId {

    /** The cross-kernel-route projection: all six channels, typed + rendered values, structured causes,
     *  applied states — compared between the two kernel routes as canonical multisets (the public
     *  collections carry no order contract; observed order stays recorded in the raw observation). */
    COMPUTE_KERNEL_ROUTE("compute-projection-kernel-route-v1"),

    /** The interpreter-comparable portable projection: target pointers, outcome classification, rendered
     *  values (computed and attempted), and value-bearing applied states. Deliberately EXCLUDED, by
     *  rationale: typed values (interpreter exposes rendered form only), structured causes (no interpreter
     *  cause transport), the changed subset (report-all granularity), operand formal errors, and the
     *  absent-vs-present-empty applied distinction (not exposed by the interpreter's public read surface).
     *  The interpreter's report-all {@code cleared} entries are restricted to input-filled cells before
     *  comparing (the kernel's cleared channel is input-filled-only). */
    COMPUTE_DMKITS_PORTABLE("compute-projection-dmkits-portable-v1");

    private final String wire;

    ProjectionId(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    /** Projection definitions are immutable; the version is part of the wire id. */
    public String version() {
        return "1";
    }

    public static Optional<ProjectionId> ofWire(String wire) {
        for (ProjectionId id : values()) {
            if (id.wire.equals(wire)) {
                return Optional.of(id);
            }
        }
        return Optional.empty();
    }
}
