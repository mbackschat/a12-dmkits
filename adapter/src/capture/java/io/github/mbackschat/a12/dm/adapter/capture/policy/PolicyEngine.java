package io.github.mbackschat.a12.dm.adapter.capture.policy;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import io.github.mbackschat.a12.dm.adapter.capture.dto.PolicyId;
import io.github.mbackschat.a12.dm.adapter.capture.dto.PolicyRequirements;
import io.github.mbackschat.a12.dm.adapter.capture.dto.Pointers;
import io.github.mbackschat.a12.dm.adapter.capture.dto.ProjectionId;
import io.github.mbackschat.a12.dm.adapter.capture.dto.RunnerId;
import io.github.mbackschat.a12.dm.adapter.capture.json.Json;
import io.github.mbackschat.a12.dm.adapter.capture.schema.ScenarioSet;

/**
 * The named qualification POLICIES (proposal §5) over an integrity-verified, IMMUTABLE packet, driven by
 * the REQUEST-DECLARED qualification contract: comparisons run for exactly the projections the request
 * PREDECLARED (the {@link PolicyRequirements} mandatory ones gate the verdict; additionally predeclared
 * ones are informational — a projection never predeclared is never compared). Every portable policy
 * rejects a development-mode / dirty-source packet BEFORE semantic comparison. A
 * {@code characterization-v1} outcome may retain runner failures and disagreements (and can never promote
 * a corpus case); {@code kernel-route-confirmed-v1} requires kernel legality + both kernel routes +
 * equality under every field of the kernel-route projection; {@code dmkits-projected-conformance-v1}
 * additionally requires the interpreter available AND equal under the portable projection — an
 * unavailable required field fails CLOSED.
 */
public final class PolicyEngine {

    /** A policy application's outcome: the verdict, the rejection reasons, and every named comparison. */
    public record Outcome(String verdict, List<String> reasons, List<Projections.Comparison> comparisons) {
        public Outcome {
            reasons = List.copyOf(reasons);
            comparisons = List.copyOf(comparisons);
        }
    }

    private PolicyEngine() {
    }

    public static Outcome apply(PolicyId policy, VerifiedPacket packet) {
        if (packet.developmentMode() || !"clean".equals(packet.workingTree())) {
            return new Outcome("rejected", List.of("development-mode / dirty-source packet — temporary"
                    + " engineering output, PERMANENTLY ineligible for every portable qualification policy"
                    + " (rejected before any semantic comparison)"), List.of());
        }
        return switch (policy) {
            case CHARACTERIZATION -> characterization(packet);
            case KERNEL_ROUTE_CONFIRMED, DMKITS_PROJECTED_CONFORMANCE -> qualifying(policy, packet);
        };
    }

    /** Schema/inventory/legality/availability truthfulness; failures and disagreements are RETAINED
     *  (comparisons are informational); never promotes. */
    private static Outcome characterization(VerifiedPacket packet) {
        List<String> reasons = new ArrayList<>();
        for (ScenarioSet.ModelRef model : packet.request().models()) {
            if (!packet.legalityVerdicts().containsKey(model.ref())) {
                reasons.add("no legality artifact for model " + model.ref());
            }
        }
        List<Projections.Comparison> comparisons = new ArrayList<>();
        for (Json caseEntry : packet.caseEntries()) {
            Json.Obj caseObj = (Json.Obj) caseEntry;
            if (!((Json.Bool) caseObj.members().get("executed")).value()) {
                continue;   // a non-executed (illegal-model) case truthfully carries no observations
            }
            String caseId = VerifiedPacket.str(caseObj, "caseId");
            for (RunnerId runner : packet.request().requiredRunners()) {
                if (packet.observation(caseId, runner.wire()) == null) {
                    reasons.add("case " + caseId + ": no observation artifact for required runner "
                            + runner.wire() + " — runner availability must be truthful");
                }
            }
            comparisons.addAll(predeclaredComparisons(packet, caseId, reasons, false));
        }
        return new Outcome(reasons.isEmpty() ? "satisfied" : "rejected", reasons, comparisons);
    }

    /** The confirmed policy; conformance additionally makes the portable projection verdict-gating. */
    private static Outcome qualifying(PolicyId policy, VerifiedPacket packet) {
        boolean requireInterpreter = policy == PolicyId.DMKITS_PROJECTED_CONFORMANCE;
        List<String> reasons = new ArrayList<>();
        packet.legalityVerdicts().forEach((ref, verdict) -> {
            if (!"legal".equals(verdict)) {
                reasons.add("model " + ref + " is kernel-ILLEGAL — an illegal model cannot satisfy a"
                        + " confirmed/conformance policy (characterization only)");
            }
        });

        List<Projections.Comparison> comparisons = new ArrayList<>();
        for (Json caseEntry : packet.caseEntries()) {
            Json.Obj caseObj = (Json.Obj) caseEntry;
            String caseId = VerifiedPacket.str(caseObj, "caseId");
            if (!((Json.Bool) caseObj.members().get("executed")).value()) {
                reasons.add("case " + caseId + " was not executed (illegal model) — no runtime evidence");
                continue;
            }
            for (RunnerId qualifying : PolicyRequirements.qualifyingRunners(policy)) {
                Json.Obj observation = packet.observation(caseId, qualifying.wire());
                if (observation == null || !"success".equals(VerifiedPacket.str(observation, "status"))) {
                    reasons.add("case " + caseId + ": qualifying runner " + qualifying.wire()
                            + " is missing or unsuccessful — fails closed");
                }
            }
            comparisons.addAll(predeclaredComparisons(packet, caseId, reasons, requireInterpreter));
        }
        return new Outcome(reasons.isEmpty() ? "satisfied" : "rejected", reasons, comparisons);
    }

    /**
     * The comparisons for exactly the PREDECLARED projections of {@code caseId} (skipping any whose
     * observations are missing/unsuccessful — those already fail closed via the qualifying-runner check
     * where the policy requires them). Divergence reasons are appended only for verdict-gating
     * projections: the kernel-route projection always gates confirmed/conformance; the portable one gates
     * only when {@code portableGates}.
     */
    private static List<Projections.Comparison> predeclaredComparisons(VerifiedPacket packet, String caseId,
            List<String> reasons, boolean portableGates) {
        Set<ProjectionId> predeclared =
                new LinkedHashSet<>(packet.request().observationRequirement().comparisonProjections());
        boolean characterizing =
                packet.request().qualificationPolicy() == PolicyId.CHARACTERIZATION;
        List<Projections.Comparison> comparisons = new ArrayList<>();

        Json.Obj groovy = successObservation(packet, caseId, RunnerId.KERNEL_GROOVY_DYNAMIC);
        Json.Obj javaStatic = successObservation(packet, caseId, RunnerId.KERNEL_JAVA_STATIC);
        if (predeclared.contains(ProjectionId.COMPUTE_KERNEL_ROUTE) && groovy != null && javaStatic != null) {
            Projections.Comparison route = Projections.kernelRoute(caseId, groovy, javaStatic);
            comparisons.add(route);
            if (!characterizing && !"equal".equals(route.result())) {
                reasons.add("case " + caseId + ": the two kernel routes DIVERGE under "
                        + route.projection().wire() + " — retained as characterization; route-confirmed"
                        + " qualification stops");
            }
        }

        Json.Obj interpreter = successObservation(packet, caseId, RunnerId.A12_DMKITS_INTERPRETER);
        if (predeclared.contains(ProjectionId.COMPUTE_DMKITS_PORTABLE) && groovy != null
                && interpreter != null) {
            Projections.Comparison portable = Projections.dmkitsPortable(caseId, groovy, interpreter,
                    inputFilledPointers(packet.request(), caseId));
            comparisons.add(portable);   // informational unless the policy makes it verdict-gating
            if (portableGates && !"equal".equals(portable.result())) {
                reasons.add("case " + caseId + ": the interpreter is not available-and-equal under "
                        + portable.projection().wire() + " (" + portable.result() + ") — fails closed");
            }
        }
        return comparisons;
    }

    private static Json.Obj successObservation(VerifiedPacket packet, String caseId, RunnerId runner) {
        Json.Obj observation = packet.observation(caseId, runner.wire());
        if (observation == null || !"success".equals(VerifiedPacket.str(observation, "status"))) {
            return null;
        }
        return observation;
    }

    /** The FIELD-placed (input-filled) cell pointers of {@code caseId} — the portable projection's
     *  cleared-granularity restriction reads the packet's own copied request. */
    private static Set<String> inputFilledPointers(ScenarioSet request, String caseId) {
        Set<String> pointers = new LinkedHashSet<>();
        for (ScenarioSet.ScenarioCase scenarioCase : request.cases()) {
            if (!scenarioCase.caseId().equals(caseId)) {
                continue;
            }
            for (ScenarioSet.Placement placement : scenarioCase.placements()) {
                if (placement.kind() == ScenarioSet.PlacementKind.FIELD) {
                    pointers.add(Pointers.format(placement.path(), placement.reps()));
                }
            }
        }
        return pointers;
    }
}
