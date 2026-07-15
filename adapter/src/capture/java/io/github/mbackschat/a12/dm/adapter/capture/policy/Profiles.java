package io.github.mbackschat.a12.dm.adapter.capture.policy;

import io.github.mbackschat.a12.dm.adapter.capture.dto.PolicyId;
import io.github.mbackschat.a12.dm.adapter.capture.dto.ProjectionId;
import io.github.mbackschat.a12.dm.adapter.capture.json.Json;
import io.github.mbackschat.a12.dm.adapter.capture.schema.SchemaIds;

/**
 * {@code PROFILE.json} ({@code qualification-profile-v1}): the PINNED policy + projection definitions a
 * verification ran under — emitted from the tool's immutable in-code definitions (a pure function, so the
 * profile bytes are deterministic and {@code REPORT.json} digest-binds them). Deliberately excluded fields
 * are NAMED with rationale, so a satisfied conformance verdict is never mistaken for full rich-result
 * conformance.
 */
public final class Profiles {

    private Profiles() {
    }

    public static Json emit(PolicyId policy) {
        Json.Obj root = Json.Obj.of()
                .put("schema", SchemaIds.QUALIFICATION_PROFILE)
                .put("policy", Json.Obj.of().put("id", policy.wire()).put("version", "1"));

        Json.Arr projections = Json.Arr.of();
        projections.add(Json.Obj.of()
                .put("id", ProjectionId.COMPUTE_KERNEL_ROUTE.wire())
                .put("version", ProjectionId.COMPUTE_KERNEL_ROUTE.version())
                .put("role", switch (policy) {
                    case CHARACTERIZATION -> "informational";
                    case KERNEL_ROUTE_CONFIRMED, DMKITS_PROJECTED_CONFORMANCE -> "required";
                })
                .put("includedFields", Json.arrOfStrings(java.util.List.of(
                        "allChannels", "typedValues", "renderedValues", "structuredCauses", "appliedStates")))
                .put("comparison", "canonical multiset per channel (the public collections carry no order"
                        + " contract; observed order stays recorded in the raw observation)"));

        Json.Arr exclusions = Json.Arr.of();
        for (String[] exclusion : Projections.PORTABLE_EXCLUSIONS) {
            exclusions.add(Json.Obj.of().put("field", exclusion[0]).put("rationale", exclusion[1]));
        }
        projections.add(Json.Obj.of()
                .put("id", ProjectionId.COMPUTE_DMKITS_PORTABLE.wire())
                .put("version", ProjectionId.COMPUTE_DMKITS_PORTABLE.version())
                .put("role", switch (policy) {
                    case CHARACTERIZATION -> "informational";
                    case KERNEL_ROUTE_CONFIRMED -> "reported";
                    case DMKITS_PROJECTED_CONFORMANCE -> "required";
                })
                .put("includedFields", Json.arrOfStrings(java.util.List.of(
                        "targets", "outcomeClassification", "renderedValues", "attemptedRenderedValues",
                        "clearedAtKernelGranularity", "appliedValueText")))
                .put("excludedFields", exclusions)
                .put("comparison", "sorted projected-string multisets; the interpreter's report-all cleared"
                        + " entries are restricted to input-filled cells before comparing"));
        root.put("projections", projections);

        root.put("rules", Json.arrOfStrings(java.util.List.of(
                "every portable policy rejects a development-mode or dirty-source packet before semantic"
                        + " comparison",
                "an unavailable required field/channel fails the policy CLOSED",
                "characterization may retain runner failures and disagreements and can never promote a"
                        + " corpus case",
                "kernel-route confirmation claims ONLY exact cross-route agreement on the pinned projection"
                        + " — one kernel implementation, never independent engines",
                "interpreter agreement is reported under the confirmed policy and required under the"
                        + " conformance policy")));
        return root;
    }
}
