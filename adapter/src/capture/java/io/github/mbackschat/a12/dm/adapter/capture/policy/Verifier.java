package io.github.mbackschat.a12.dm.adapter.capture.policy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import io.github.mbackschat.a12.dm.adapter.capture.dto.PolicyId;
import io.github.mbackschat.a12.dm.adapter.capture.json.Digests;
import io.github.mbackschat.a12.dm.adapter.capture.json.Json;
import io.github.mbackschat.a12.dm.adapter.capture.packet.CaptureRefusedException;
import io.github.mbackschat.a12.dm.adapter.capture.packet.LeakGuard;
import io.github.mbackschat.a12.dm.adapter.capture.packet.OutputDirs;
import io.github.mbackschat.a12.dm.adapter.capture.packet.PacketWriter;
import io.github.mbackschat.a12.dm.adapter.capture.packet.Receipt;
import io.github.mbackschat.a12.dm.adapter.capture.packet.SourceState;
import io.github.mbackschat.a12.dm.adapter.capture.schema.SchemaIds;

/**
 * The VERIFIER (proposal §5): applies the REQUEST-DECLARED policy to an immutable, receipt-bound packet
 * and writes a SEPARATE digest-bound report directory ({@code PROFILE.json} + {@code REPORT.json} + its
 * own {@code RECEIPT.json}) — verification never adds to or rewrites the packet. Verification is BOUND to
 * the request's declared qualification policy: a mismatching CLI policy is refused fail-closed before any
 * comparison (a policy is never selected after observations are known). The report binds the exact
 * upstream receipt digest, the request digest + declared observation-requirement identity, the profile
 * digest, and the verifier's own source identity.
 */
public final class Verifier {

    /** A written report: its receipt digest and the policy verdict it carries. */
    public record Verified(String reportReceiptSha256, String verdict) {
    }

    private Verifier() {
    }

    public static Verified verify(Path packetDir, String packetReceiptSha256, PolicyId policy,
            Path outputDir, SourceState verifierState, Path repoRoot) {
        OutputDirs.requireNewOrEmptyOutsideCorpus(outputDir, repoRoot);
        VerifiedPacket packet = VerifiedPacket.load(packetDir, packetReceiptSha256);
        PolicyId declared = packet.request().qualificationPolicy();
        if (policy != declared) {
            throw new CaptureRefusedException("the request declares qualification policy \"" + declared.wire()
                    + "\" — verification is BOUND to the request-declared policy (pinned before execution);"
                    + " the mismatching CLI policy \"" + policy.wire() + "\" is refused, fail closed. A"
                    + " policy is never selected after observations are known.");
        }
        PolicyEngine.Outcome outcome = PolicyEngine.apply(policy, packet);

        byte[] profileBytes = Json.canonicalBytes(Profiles.emit(policy));
        byte[] reportBytes = Json.canonicalBytes(report(policy, packet, outcome,
                Digests.sha256(profileBytes), verifierState));

        LeakGuard leakGuard = new LeakGuard(repoRoot.toAbsolutePath().toString(),
                outputDir.toAbsolutePath().toString(), packetDir.toAbsolutePath().toString());
        leakGuard.checkGenerated("PROFILE.json", profileBytes);
        leakGuard.checkGenerated("REPORT.json", reportBytes);

        try {
            Files.createDirectories(outputDir);
            Files.write(outputDir.resolve("PROFILE.json"), profileBytes);
            Files.write(outputDir.resolve("REPORT.json"), reportBytes);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to write the report", e);
        }
        String receiptSha = Receipt.over(outputDir).write(outputDir);
        return new Verified(receiptSha, outcome.verdict());
    }

    private static Json report(PolicyId policy, VerifiedPacket packet, PolicyEngine.Outcome outcome,
            String profileSha256, SourceState verifierState) {
        var requirement = packet.request().observationRequirement();
        Json.Obj root = Json.Obj.of()
                .put("schema", SchemaIds.QUALIFICATION_REPORT)
                .put("policy", Json.Obj.of().put("id", policy.wire()).put("version", "1"))
                // The request-declared qualification contract this report is BOUND to: changing the
                // declared requirement's identity or contents changes the request bytes, hence this digest,
                // hence the report identity.
                .put("request", Json.Obj.of()
                        .put("scenarioSetId", packet.request().scenarioSetId())
                        .put("scenarioSetVersion", packet.request().scenarioSetVersion())
                        .put("requestSha256", packet.requestSha256())
                        .put("qualificationPolicy", packet.request().qualificationPolicy().wire())
                        .put("observationRequirement", Json.Obj.of()
                                .put("id", requirement.id())
                                .put("version", requirement.version())
                                .put("requiredChannels", Json.arrOfStrings(requirement.requiredChannels()
                                        .stream().map(c -> c.wire()).toList()))
                                .put("comparisonProjections", Json.arrOfStrings(requirement
                                        .comparisonProjections().stream().map(p -> p.wire()).toList()))))
                .put("packet", Json.Obj.of()
                        .put("receiptSha256", packet.receiptSha256())
                        .put("sourceRevision", packet.sourceRevision())
                        .put("workingTree", packet.workingTree()))
                .put("profileSha256", profileSha256)
                .put("verifier", Json.Obj.of()
                        .put("tool", PacketWriter.TOOL_NAME)
                        .put("toolVersion", PacketWriter.TOOL_VERSION)
                        .put("sourceRevision", verifierState.revision())
                        .put("workingTree", verifierState.workingTreeWire())
                        .put("treeStateDigest", verifierState.treeStateDigest()))
                .put("verdict", outcome.verdict())
                .put("reasons", Json.arrOfStrings(outcome.reasons()));

        Json.Arr comparisons = Json.Arr.of();
        for (Projections.Comparison comparison : outcome.comparisons()) {
            Json.Arr channels = Json.Arr.of();
            for (Projections.ChannelResult channel : comparison.channels()) {
                channels.add(Json.Obj.of()
                        .put("channel", channel.channel())
                        .put("result", channel.result())
                        .put("detail", channel.detail() == null
                                ? Json.Null.NULL : new Json.Str(channel.detail())));
            }
            comparisons.add(Json.Obj.of()
                    .put("caseId", comparison.caseId())
                    .put("runnerA", comparison.runnerA().wire())
                    .put("runnerB", comparison.runnerB().wire())
                    .put("projection", comparison.projection().wire())
                    .put("projectionVersion", comparison.projection().version())
                    .put("includedFields", Json.arrOfStrings(comparison.includedFields()))
                    .put("unavailableFields", Json.arrOfStrings(comparison.unavailableFields()))
                    .put("channels", channels)
                    .put("result", comparison.result()));
        }
        root.put("comparisons", comparisons);
        return root;
    }
}
