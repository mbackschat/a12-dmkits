package io.github.mbackschat.a12.dm.adapter.capture.dto;

import java.util.List;
import java.util.Set;

/**
 * The compile-time policy-requirements REGISTRY — the one source for what each qualification policy
 * demands, consumed by the pre-execution gate (the scenario decoder), the policy engine, and the
 * capability output alike, so policy semantics can never drift between the surfaces.
 *
 * <p>The exact semantics of the request's global {@code requiredChannels}: every required channel must be
 * declared {@code available} in the registry fidelity of every runner the DECLARED policy requires to
 * succeed (its {@link #qualifyingRunners}); a runner requested only as triangulation/characterization
 * keeps its declared fidelity gaps — echoed, never bridged, never a refusal. Per-runner or per-field
 * requirements exceed what {@code capture-scenario-set-v1} can express and take a NEW request schema,
 * never a silent extension.
 */
public final class PolicyRequirements {

    private PolicyRequirements() {
    }

    /** The runners the policy requires to SUCCEED — the request must include them, every required channel
     *  must be registry-available on them, and the policy fails closed when one is missing/unsuccessful. */
    public static Set<RunnerId> qualifyingRunners(PolicyId policy) {
        return switch (policy) {
            // Characterization's bar is truthful availability, never a successful runner.
            case CHARACTERIZATION -> Set.of();
            case KERNEL_ROUTE_CONFIRMED -> Set.of(RunnerId.KERNEL_GROOVY_DYNAMIC, RunnerId.KERNEL_JAVA_STATIC);
            case DMKITS_PROJECTED_CONFORMANCE -> Set.of(RunnerId.KERNEL_GROOVY_DYNAMIC,
                    RunnerId.KERNEL_JAVA_STATIC, RunnerId.A12_DMKITS_INTERPRETER);
        };
    }

    /** The projections whose equality the policy's verdict rests on — every one of them must be
     *  PREDECLARED in the request's {@code comparisonProjections} (pinned before execution, so a
     *  comparison can never be selected or weakened after observations are known). */
    public static List<ProjectionId> mandatoryProjections(PolicyId policy) {
        return switch (policy) {
            case CHARACTERIZATION -> List.of();
            case KERNEL_ROUTE_CONFIRMED -> List.of(ProjectionId.COMPUTE_KERNEL_ROUTE);
            case DMKITS_PROJECTED_CONFORMANCE -> List.of(ProjectionId.COMPUTE_KERNEL_ROUTE,
                    ProjectionId.COMPUTE_DMKITS_PORTABLE);
        };
    }
}
