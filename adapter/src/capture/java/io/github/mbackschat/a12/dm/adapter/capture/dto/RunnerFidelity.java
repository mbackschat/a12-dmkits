package io.github.mbackschat.a12.dm.adapter.capture.dto;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A runner's DECLARED per-channel fidelity (proposal §2/§4) — what the runner's public surface exposes,
 * fixed by the compile-time registry and echoed into capabilities output and packet runner descriptors.
 * Honest by construction: the interpreter's missing typed values / structured causes / changed subset /
 * operand formal errors / absent-vs-present-empty distinction are declared {@code notExposedByRunner},
 * never rendered as empty observations or inferred from kernel output.
 */
public record RunnerFidelity(
        Map<ChannelId, ChannelFidelity> channels,
        Availability typedValues,
        Availability renderedValues,
        Availability structuredCauses,
        Availability absentVsPresentEmpty) {

    public RunnerFidelity {
        channels = Map.copyOf(channels);
    }

    /** One channel's availability + reporting granularity for a runner. */
    public record ChannelFidelity(Availability availability, String granularity) {
    }

    /** The declared fidelity of {@code channel} (every runner declares all six; iterate
     *  {@link ChannelId#values()} for the pinned output order — the map itself is unordered). */
    public ChannelFidelity channel(ChannelId channel) {
        return channels.get(channel);
    }

    // The closed granularity vocabulary (pinned wire strings).
    public static final String ALL_COMPUTED_CLEAN = "all-computed-clean";
    public static final String DELTA_VS_INPUT = "delta-vs-input";
    public static final String ERRORED_INSTANCES = "errored-instances";
    public static final String INPUT_FILLED_ONLY = "input-filled-only";
    public static final String ALL_CLEARED = "all-cleared";
    public static final String OPERAND_FORMAL_ERRORS = "operand-formal-errors";
    public static final String REQUESTED_PROBES = "requested-probes";
    public static final String NOT_EXPOSED = "notExposed";

    /** The registry-declared fidelity of {@code runner}. */
    public static RunnerFidelity of(RunnerId runner) {
        return switch (runner) {
            case KERNEL_GROOVY_DYNAMIC, KERNEL_JAVA_STATIC -> kernelRouteFidelity();
            case A12_DMKITS_INTERPRETER -> interpreterFidelity();
        };
    }

    private static RunnerFidelity kernelRouteFidelity() {
        Map<ChannelId, ChannelFidelity> channels = new LinkedHashMap<>();
        channels.put(ChannelId.WITHOUT_ERRORS, new ChannelFidelity(Availability.AVAILABLE, ALL_COMPUTED_CLEAN));
        channels.put(ChannelId.CHANGED_SUBSET, new ChannelFidelity(Availability.AVAILABLE, DELTA_VS_INPUT));
        channels.put(ChannelId.WITH_ERRORS, new ChannelFidelity(Availability.AVAILABLE, ERRORED_INSTANCES));
        channels.put(ChannelId.CLEARED, new ChannelFidelity(Availability.AVAILABLE, INPUT_FILLED_ONLY));
        channels.put(ChannelId.FORMAL_ERRORS_IN_OPERANDS,
                new ChannelFidelity(Availability.AVAILABLE, OPERAND_FORMAL_ERRORS));
        channels.put(ChannelId.APPLIED_STATE, new ChannelFidelity(Availability.AVAILABLE, REQUESTED_PROBES));
        return new RunnerFidelity(channels, Availability.AVAILABLE, Availability.AVAILABLE,
                Availability.AVAILABLE, Availability.AVAILABLE);
    }

    private static RunnerFidelity interpreterFidelity() {
        Map<ChannelId, ChannelFidelity> channels = new LinkedHashMap<>();
        channels.put(ChannelId.WITHOUT_ERRORS, new ChannelFidelity(Availability.AVAILABLE, ALL_COMPUTED_CLEAN));
        channels.put(ChannelId.CHANGED_SUBSET, new ChannelFidelity(Availability.NOT_EXPOSED_BY_RUNNER, NOT_EXPOSED));
        channels.put(ChannelId.WITH_ERRORS, new ChannelFidelity(Availability.AVAILABLE, ERRORED_INSTANCES));
        channels.put(ChannelId.CLEARED, new ChannelFidelity(Availability.AVAILABLE, ALL_CLEARED));
        channels.put(ChannelId.FORMAL_ERRORS_IN_OPERANDS,
                new ChannelFidelity(Availability.NOT_EXPOSED_BY_RUNNER, NOT_EXPOSED));
        channels.put(ChannelId.APPLIED_STATE, new ChannelFidelity(Availability.AVAILABLE, REQUESTED_PROBES));
        return new RunnerFidelity(channels, Availability.NOT_EXPOSED_BY_RUNNER, Availability.AVAILABLE,
                Availability.NOT_EXPOSED_BY_RUNNER, Availability.NOT_EXPOSED_BY_RUNNER);
    }
}
