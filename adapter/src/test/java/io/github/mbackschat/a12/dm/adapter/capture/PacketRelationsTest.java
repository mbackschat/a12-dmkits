package io.github.mbackschat.a12.dm.adapter.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.UnaryOperator;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.mbackschat.a12.dm.adapter.capture.json.Digests;
import io.github.mbackschat.a12.dm.adapter.capture.packet.Receipt;

/**
 * The CLOSED RELATIONAL packet validator (the external audit's Finding 2): byte integrity alone is not
 * enough — a receipt-consistent but internally contradictory packet must REFUSE verification. Every
 * mutation here renews the receipt (and re-binds envelope digests where an artifact changed), so the
 * integrity layer stays satisfied and only the relational layer can catch the contradiction: the envelope
 * must describe exactly the copied inputs, artifacts, registry echoes, and request the packet actually
 * contains — every claimed association verified, every artifact accounted for exactly once, unknown
 * nested members rejected.
 */
class PacketRelationsTest {

    @TempDir
    Path work;

    Path packet;

    @BeforeEach
    void captureFresh() {
        Path scenario = CaptureFixtures.writeValidScenario(work.resolve("root"));
        packet = work.resolve("packet");
        CaptureCommandTest.Run run = CaptureCommandTest.run(work, CaptureCommandTest.CLEAN,
                "capture", "--scenarios", scenario.toString(), "--output", packet.toString());
        assertEquals(0, run.exit(), run::err);
    }

    /** Mutate PACKET.json text and RENEW the receipt; returns the new out-of-band digest. */
    private String mutateEnvelope(UnaryOperator<String> mutate) throws IOException {
        Path envelope = packet.resolve("PACKET.json");
        String text = Files.readString(envelope, StandardCharsets.UTF_8);
        String mutated = mutate.apply(text);
        if (mutated.equals(text)) {
            throw new IllegalStateException("envelope mutation needle did not match");
        }
        Files.writeString(envelope, mutated, StandardCharsets.UTF_8);
        return Receipt.over(packet).write(packet);
    }

    /** Mutate an artifact, re-bind its envelope digest, RENEW the receipt; returns the new digest. */
    private String mutateArtifactRebound(String relPath, UnaryOperator<String> mutate) throws IOException {
        Path artifact = packet.resolve(relPath);
        String oldSha = Digests.sha256(Files.readAllBytes(artifact));
        String text = Files.readString(artifact, StandardCharsets.UTF_8);
        String mutated = mutate.apply(text);
        if (mutated.equals(text)) {
            throw new IllegalStateException("artifact mutation needle did not match: " + relPath);
        }
        Files.writeString(artifact, mutated, StandardCharsets.UTF_8);
        String newSha = Digests.sha256(Files.readAllBytes(artifact));
        Path envelope = packet.resolve("PACKET.json");
        Files.writeString(envelope, Files.readString(envelope).replace(oldSha, newSha));
        return Receipt.over(packet).write(packet);
    }

    private void expectRefusal(String sha, String... diagnosticNeedles) {
        CaptureCommandTest.Run run = CaptureCommandTest.run(work, CaptureCommandTest.CLEAN,
                "verify", "--packet", packet.toString(), "--packet-receipt-sha256", sha,
                "--policy", "kernel-route-confirmed-v1", "--output",
                work.resolve("report-" + sha.substring(0, 8)).toString());
        assertEquals(1, run.exit(), () -> "must REFUSE (not report): " + run.out());
        for (String needle : diagnosticNeedles) {
            assertTrue(run.err().contains(needle), () -> "diagnostic must name \"" + needle + "\": " + run.err());
        }
    }

    @Test
    void aChangedRequestDigestClaimRefuses() throws IOException {
        String sha = mutateEnvelope(text -> text.replaceFirst(
                "\"requestSha256\": \"[0-9a-f]{64}\"", "\"requestSha256\": \"" + "1".repeat(64) + "\""));
        expectRefusal(sha, "requestSha256");
    }

    @Test
    void aChangedScenarioIdentityClaimRefuses() throws IOException {
        String sha = mutateEnvelope(text -> text.replace(
                "\"id\": \"capture-selftest-quote-v1\"", "\"id\": \"someone-elses-scenario-v1\""));
        expectRefusal(sha, "scenario");
    }

    @Test
    void aFlippedLegalityVerdictClaimRefuses() throws IOException {
        // The envelope claims "illegal" while the legality artifact says "legal" — an internal
        // contradiction, refused before any policy could even read it.
        String sha = mutateEnvelope(text -> text.replace("\"verdict\": \"legal\"", "\"verdict\": \"illegal\""));
        expectRefusal(sha, "verdict");
    }

    @Test
    void aChangedInputDigestClaimRefuses() throws IOException {
        String sha = mutateEnvelope(text -> text.replaceFirst(
                "\"suppliedSha256\": \"[0-9a-f]{64}\"", "\"suppliedSha256\": \"" + "2".repeat(64) + "\""));
        expectRefusal(sha, "supplied");
    }

    @Test
    void anObservationClaimingAnotherCaseRefuses() throws IOException {
        String sha = mutateArtifactRebound("observations/premium-computes/kernel-groovy-dynamic.json",
                text -> text.replace("\"caseId\": \"premium-computes\"", "\"caseId\": \"another-case\""));
        expectRefusal(sha, "caseId");
    }

    @Test
    void anObservationClaimingAnotherRunnerRefuses() throws IOException {
        String sha = mutateArtifactRebound("observations/premium-computes/kernel-java-static.json",
                text -> text.replace("\"runner\": \"kernel-java-static\"",
                        "\"runner\": \"kernel-groovy-dynamic\""));
        expectRefusal(sha, "runner");
    }

    @Test
    void anObservationStatusContradictingItsEnvelopeRefRefuses() throws IOException {
        String sha = mutateArtifactRebound("observations/premium-computes/kernel-java-static.json",
                text -> text.replaceFirst("\"status\": \"success\"", "\"status\": \"technicalFailure\""));
        expectRefusal(sha, "status");
    }

    @Test
    void aConsumedDocumentMismatchingTheRequestCaseRefuses() throws IOException {
        String sha = mutateArtifactRebound("observations/premium-computes/kernel-groovy-dynamic.json",
                text -> text.replaceFirst("\"canonicalSha256\": \"[0-9a-f]{64}\"",
                        "\"canonicalSha256\": \"" + "3".repeat(64) + "\""));
        expectRefusal(sha, "consumed");
    }

    @Test
    void forgedChannelAvailabilityContradictingTheRegistryRefuses() throws IOException {
        String sha = mutateArtifactRebound("observations/premium-computes/a12-dmkits-interpreter.json",
                text -> text.replaceFirst("\"withoutErrors\": \\{\\n      \"availability\": \"available\"",
                        "\"withoutErrors\": {\n      \"availability\": \"notExposedByRunner\""));
        expectRefusal(sha, "availability");
    }

    @Test
    void aTamperedCapabilitiesCopyRefuses() throws IOException {
        String sha = mutateArtifactRebound("capabilities/capabilities.json",
                text -> text.replace("\"all-computed-clean\"", "\"all-clean\""));
        expectRefusal(sha, "capabilities");
    }

    @Test
    void anUnknownNestedEnvelopeMemberRefuses() throws IOException {
        String sha = mutateEnvelope(text -> text.replace("\"requestArtifact\": \"request/scenarios.json\",",
                "\"requestArtifact\": \"request/scenarios.json\",\n    \"note\": \"smuggled\","));
        expectRefusal(sha, "note");
    }

    @Test
    void anUnknownNestedObservationMemberRefuses() throws IOException {
        String sha = mutateArtifactRebound("observations/premium-computes/kernel-groovy-dynamic.json",
                text -> text.replace("\"schema\": \"compute-observation-v1\",",
                        "\"schema\": \"compute-observation-v1\",\n  \"surprise\": true,"));
        expectRefusal(sha, "surprise");
    }

    @Test
    void aRemovedObservationAssociationRefuses() throws IOException {
        // Drop the java-static ref from the case's observations — the artifact file remains, so the
        // receipt still matches; only exactly-once accounting can catch the orphan.
        String sha = mutateEnvelope(text -> text.replaceFirst(
                ",\\s*\\{\\s*\"runner\": \"kernel-java-static\",[^}]*\\}", ""));
        expectRefusal(sha, "kernel-java-static");
    }

    @Test
    void anOrphanObservationArtifactRefuses() throws IOException {
        // A well-formed EXTRA artifact nothing references: receipt-consistent, relationally orphaned.
        Path extra = packet.resolve("observations/premium-computes/extra.json");
        Files.copy(packet.resolve("observations/premium-computes/kernel-groovy-dynamic.json"), extra);
        String sha = Receipt.over(packet).write(packet);
        expectRefusal(sha, "extra.json");
    }
}
