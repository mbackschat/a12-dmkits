package io.github.mbackschat.a12.dm.adapter.capture.schema;

import java.util.List;

import io.github.mbackschat.a12.dm.adapter.capture.dto.ChannelId;
import io.github.mbackschat.a12.dm.adapter.capture.dto.PolicyId;
import io.github.mbackschat.a12.dm.adapter.capture.dto.ProjectionId;
import io.github.mbackschat.a12.dm.adapter.capture.dto.RunnerFidelity;
import io.github.mbackschat.a12.dm.adapter.capture.dto.RunnerId;
import io.github.mbackschat.a12.dm.adapter.capture.json.Json;

/**
 * The closed, independently versioned {@code capabilities} output (proposal §8): supported schemas,
 * input-scope versions, runner IDs + roles + per-channel fidelity, projection/policy IDs, limits, and
 * command support. Capabilities describe TRANSPORT, never A12 semantic support — supporting
 * {@code compute-observation-v1} means the tool can capture its closed fields; it does not claim the
 * interpreter implements every computation operator.
 *
 * <p>The output is a pure function of the compile-time registry, so a schema/projection mutation under an
 * existing ID fails the committed golden fixture ({@code CapabilitiesTest}) — the compatibility fixture
 * §8 requires.
 */
public final class Capabilities {

    private Capabilities() {
    }

    public static Json emit() {
        Json.Obj root = Json.Obj.of()
                .put("schema", SchemaIds.CAPABILITIES)
                .put("capabilitiesVersion", SchemaIds.CAPABILITIES_VERSION)
                .put("describes", "transport-capability, never A12 semantic support")
                .put("requestSchemas", Json.arrOfStrings(List.of(SchemaIds.SCENARIO_SET)))
                .put("envelopeSchemas", Json.arrOfStrings(List.of(SchemaIds.EVIDENCE_PACKET)))
                .put("receiptSchema", SchemaIds.RECEIPT)
                .put("legalitySchema", SchemaIds.LEGALITY);
        Json.Arr operations = Json.Arr.of().add(Json.Obj.of()
                .put("id", SchemaIds.COMPUTE_OBSERVATION)
                .put("inputSupport", SchemaIds.INPUT_SUPPORT_COMPUTE)
                .put("repeatableComputationTargets", "rejected")
                .put("channels", Json.arrOfStrings(
                        java.util.Arrays.stream(ChannelId.values()).map(ChannelId::wire).toList())));
        root.put("operationSchemas", operations);
        root.put("worldProfiles", Json.arrOfStrings(List.of(SchemaIds.WORLD_PROFILE_LOCAL_WALLCLOCK)))
                .put("locales", Json.arrOfStrings(List.of("en_US")));

        Json.Arr runners = Json.Arr.of();
        for (RunnerId id : RunnerId.values()) {
            runners.add(runner(id));
        }
        root.put("runners", runners);

        root.put("projections", Json.arrOfStrings(
                        java.util.Arrays.stream(ProjectionId.values()).map(ProjectionId::wire).toList()))
                .put("policies", Json.arrOfStrings(
                        java.util.Arrays.stream(PolicyId.values()).map(PolicyId::wire).toList()))
                .put("reportSchemas", Json.arrOfStrings(List.of(
                        SchemaIds.QUALIFICATION_PROFILE, SchemaIds.QUALIFICATION_REPORT,
                        SchemaIds.PACKET_DIFF_REPORT, SchemaIds.REPORT_DIFF_REPORT)))
                .put("commands", Json.arrOfStrings(List.of(
                        "capabilities", "validate-scenarios", "capture", "verify", "diff-packets", "diff-reports")));
        return root;
    }

    private static Json runner(RunnerId id) {
        RunnerFidelity fidelity = RunnerFidelity.of(id);
        Json.Obj channels = Json.Obj.of();
        for (ChannelId channel : ChannelId.values()) {   // enum order = the pinned member order
            RunnerFidelity.ChannelFidelity f = fidelity.channel(channel);
            channels.put(channel.wire(), Json.Obj.of()
                    .put("availability", f.availability().wire())
                    .put("granularity", f.granularity()));
        }
        return Json.Obj.of()
                .put("id", id.wire())
                .put("role", id.role())
                .put("route", id.route())
                .put("channels", channels)
                .put("typedValues", fidelity.typedValues().wire())
                .put("renderedValues", fidelity.renderedValues().wire())
                .put("structuredCauses", fidelity.structuredCauses().wire())
                .put("absentVsPresentEmpty", fidelity.absentVsPresentEmpty().wire());
    }
}
