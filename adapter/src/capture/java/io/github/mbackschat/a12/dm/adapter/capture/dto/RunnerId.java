package io.github.mbackschat.a12.dm.adapter.capture.dto;

import java.util.Optional;

/**
 * The COMPILE-TIME runner registry (proposal §3): runner identity, role, and route are defined here and
 * echoed from the resolved runner — never runtime plugin discovery. The two kernel names invoke provably
 * distinct registered routes ({@code RouteDistinctnessTest} does-fires on a collapse), but the claim stays
 * <b>cross-route agreement within one kernel implementation</b> — never independent engines. The
 * interpreter is clean-room triangulation, never the oracle.
 */
public enum RunnerId {

    /** The behavioral anchor: the kernel's md-runtime DYNAMIC (Groovy) service route. */
    KERNEL_GROOVY_DYNAMIC("kernel-groovy-dynamic", "anchor", "kernel-md-runtime dynamic service"),

    /** Cross-route confirmation THROUGH THE SAME kernel artifact: the static generated-JAVA codegen route. */
    KERNEL_JAVA_STATIC("kernel-java-static", "cross-route-confirmation", "kernel static generated-java codegen"),

    /** Clean-room triangulation: the kernel-free a12-dmkits interpreter — never the oracle. */
    A12_DMKITS_INTERPRETER("a12-dmkits-interpreter", "triangulation", "a12-dmkits interpreter");

    private final String wire;
    private final String role;
    private final String route;

    RunnerId(String wire, String role, String route) {
        this.wire = wire;
        this.role = role;
        this.route = route;
    }

    /** The registered runner id as it appears in requests and packets. */
    public String wire() {
        return wire;
    }

    /** The runner's declared role in the evidence claim (anchor / cross-route-confirmation / triangulation). */
    public String role() {
        return role;
    }

    /** The registered route description (implementation-neutral wording — no kernel class names). */
    public String route() {
        return route;
    }

    public static Optional<RunnerId> ofWire(String wire) {
        for (RunnerId id : values()) {
            if (id.wire.equals(wire)) {
                return Optional.of(id);
            }
        }
        return Optional.empty();
    }
}
