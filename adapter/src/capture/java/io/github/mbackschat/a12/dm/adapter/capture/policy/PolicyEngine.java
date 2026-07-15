package io.github.mbackschat.a12.dm.adapter.capture.policy;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import io.github.mbackschat.a12.dm.adapter.capture.dto.PolicyId;
import io.github.mbackschat.a12.dm.adapter.capture.dto.Pointers;
import io.github.mbackschat.a12.dm.adapter.capture.dto.RunnerId;
import io.github.mbackschat.a12.dm.adapter.capture.json.Json;
import io.github.mbackschat.a12.dm.adapter.capture.schema.ScenarioSet;

/**
 * The named qualification POLICIES (proposal §5) over an integrity-verified, IMMUTABLE packet. Every
 * portable policy rejects a development-mode / dirty-source packet BEFORE semantic comparison. A
 * {@code characterization-v1} outcome may retain runner failures and disagreements (and can never promote
 * a corpus case); {@code kernel-route-confirmed-v1} requires kernel legality + both kernel routes +
 * equality under every field of the kernel-route projection (interpreter agreement reported, not
 * required); {@code dmkits-projected-conformance-v1} additionally requires the interpreter available AND
 * equal under the portable projection — an unavailable required field fails CLOSED.
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
            case KERNEL_ROUTE_CONFIRMED -> kernelRouteConfirmed(packet, false);
            case DMKITS_PROJECTED_CONFORMANCE -> kernelRouteConfirmed(packet, true);
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
        }
        return new Outcome(reasons.isEmpty() ? "satisfied" : "rejected", reasons,
                kernelRouteComparisons(packet));
    }

    /** The confirmed policy; with {@code requireInterpreter} it becomes the conformance policy. */
    private static Outcome kernelRouteConfirmed(VerifiedPacket packet, boolean requireInterpreter) {
        List<String> reasons = new ArrayList<>();
        packet.legalityVerdicts().forEach((ref, verdict) -> {
            if (!"legal".equals(verdict)) {
                reasons.add("model " + ref + " is kernel-ILLEGAL — an illegal model cannot satisfy a"
                        + " confirmed/conformance policy (characterization only)");
            }
        });
        Set<RunnerId> required = new HashSet<>(packet.request().requiredRunners());
        if (!required.contains(RunnerId.KERNEL_GROOVY_DYNAMIC) || !required.contains(RunnerId.KERNEL_JAVA_STATIC)) {
            reasons.add("both kernel routes are required and one was not requested — fails closed");
        }
        if (requireInterpreter && !required.contains(RunnerId.A12_DMKITS_INTERPRETER)) {
            reasons.add("the interpreter runner is required by this policy and was not requested — fails closed");
        }

        List<Projections.Comparison> comparisons = new ArrayList<>();
        for (Json caseEntry : packet.caseEntries()) {
            Json.Obj caseObj = (Json.Obj) caseEntry;
            String caseId = VerifiedPacket.str(caseObj, "caseId");
            if (!((Json.Bool) caseObj.members().get("executed")).value()) {
                reasons.add("case " + caseId + " was not executed (illegal model) — no runtime evidence");
                continue;
            }
            Json.Obj groovy = packet.observation(caseId, RunnerId.KERNEL_GROOVY_DYNAMIC.wire());
            Json.Obj javaStatic = packet.observation(caseId, RunnerId.KERNEL_JAVA_STATIC.wire());
            if (groovy == null || javaStatic == null
                    || !"success".equals(VerifiedPacket.str(groovy, "status"))
                    || !"success".equals(VerifiedPacket.str(javaStatic, "status"))) {
                reasons.add("case " + caseId + ": a required kernel route is missing or unsuccessful —"
                        + " fails closed");
                continue;
            }
            Projections.Comparison route = Projections.kernelRoute(caseId, groovy, javaStatic);
            comparisons.add(route);
            if (!"equal".equals(route.result())) {
                reasons.add("case " + caseId + ": the two kernel routes DIVERGE under "
                        + route.projection().wire() + " — retained as characterization; route-confirmed"
                        + " qualification stops");
            }

            Json.Obj interpreter = packet.observation(caseId, RunnerId.A12_DMKITS_INTERPRETER.wire());
            if (interpreter != null && "success".equals(VerifiedPacket.str(interpreter, "status"))) {
                Projections.Comparison portable = Projections.dmkitsPortable(caseId, groovy, interpreter,
                        inputFilledPointers(packet.request(), caseId));
                comparisons.add(portable);   // reported for confirmed; required for conformance
                if (requireInterpreter && !"equal".equals(portable.result())) {
                    reasons.add("case " + caseId + ": the interpreter is not available-and-equal under "
                            + portable.projection().wire() + " (" + portable.result() + ") — fails closed");
                }
            } else if (requireInterpreter) {
                reasons.add("case " + caseId + ": the interpreter observation is missing or unsuccessful —"
                        + " a required runner fails the policy closed");
            }
        }
        return new Outcome(reasons.isEmpty() ? "satisfied" : "rejected", reasons, comparisons);
    }

    private static List<Projections.Comparison> kernelRouteComparisons(VerifiedPacket packet) {
        List<Projections.Comparison> comparisons = new ArrayList<>();
        for (Json caseEntry : packet.caseEntries()) {
            Json.Obj caseObj = (Json.Obj) caseEntry;
            String caseId = VerifiedPacket.str(caseObj, "caseId");
            Json.Obj groovy = packet.observation(caseId, RunnerId.KERNEL_GROOVY_DYNAMIC.wire());
            Json.Obj javaStatic = packet.observation(caseId, RunnerId.KERNEL_JAVA_STATIC.wire());
            if (groovy != null && javaStatic != null
                    && "success".equals(VerifiedPacket.str(groovy, "status"))
                    && "success".equals(VerifiedPacket.str(javaStatic, "status"))) {
                comparisons.add(Projections.kernelRoute(caseId, groovy, javaStatic));
            }
        }
        return comparisons;
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
