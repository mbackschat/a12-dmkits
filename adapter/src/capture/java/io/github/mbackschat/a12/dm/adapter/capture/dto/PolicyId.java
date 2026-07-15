package io.github.mbackschat.a12.dm.adapter.capture.dto;

import java.util.Optional;

/**
 * The named, versioned, IMMUTABLE qualification policies (proposal §5). Verification applies a policy to
 * an immutable packet and writes a separate digest-bound report — it never rewrites the packet. Every
 * portable policy rejects a development-mode / dirty-source packet before semantic comparison.
 */
public enum PolicyId {

    /** Schema/inventory/legality/availability truthfulness; may retain failures and disagreements; can
     *  never promote a corpus case. */
    CHARACTERIZATION("characterization-v1"),

    /** Kernel-legal scenario + both kernel routes successful + equality under every required field of the
     *  pinned kernel-route projection; interpreter agreement reported, not required. Claims ONLY exact
     *  cross-route agreement on that projection. */
    KERNEL_ROUTE_CONFIRMED("kernel-route-confirmed-v1"),

    /** Additionally requires the a12-dmkits interpreter to make every field of its pinned portable
     *  projection available AND equal; an unavailable required field fails closed; deliberately excluded
     *  fields are named with rationale — never "full rich-result conformance". */
    DMKITS_PROJECTED_CONFORMANCE("dmkits-projected-conformance-v1");

    private final String wire;

    PolicyId(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    public static Optional<PolicyId> ofWire(String wire) {
        for (PolicyId id : values()) {
            if (id.wire.equals(wire)) {
                return Optional.of(id);
            }
        }
        return Optional.empty();
    }
}
