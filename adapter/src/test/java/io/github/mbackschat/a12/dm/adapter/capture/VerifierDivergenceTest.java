package io.github.mbackschat.a12.dm.adapter.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.mbackschat.a12.dm.adapter.capture.json.Digests;
import io.github.mbackschat.a12.dm.adapter.capture.packet.Receipt;

/**
 * Divergence and fail-closed handling (proposal §5): a cross-route split REJECTS route confirmation while
 * characterization RETAINS it; an interpreter channel declared unavailable fails the conformance policy
 * CLOSED (never counted as agreement); and undeclared byte drift refuses verification outright.
 *
 * <p>Divergences are manufactured by tampering an observation artifact and CONSISTENTLY re-binding the
 * envelope + receipt (the packet stays integrity-consistent, so the POLICY layer — not the integrity
 * layer — must catch the disagreement).
 */
class VerifierDivergenceTest {

    /** Tamper one observation artifact via {@code mutate}, then re-bind the envelope ref + receipt so the
     *  packet stays receipt-consistent; returns the new out-of-band digest. */
    private static String tamperObservation(Path packet, String caseId, String runner,
            java.util.function.UnaryOperator<String> mutate) throws IOException {
        Path artifact = packet.resolve("observations/" + caseId + "/" + runner + ".json");
        String oldSha = Digests.sha256(Files.readAllBytes(artifact));
        String mutated = mutate.apply(Files.readString(artifact, StandardCharsets.UTF_8));
        Files.writeString(artifact, mutated, StandardCharsets.UTF_8);
        String newSha = Digests.sha256(Files.readAllBytes(artifact));

        Path envelope = packet.resolve("PACKET.json");
        Files.writeString(envelope, Files.readString(envelope).replace(oldSha, newSha), StandardCharsets.UTF_8);
        return Receipt.over(packet).write(packet);
    }

    private static Path capture(Path work) {
        Path scenario = CaptureFixtures.writeValidScenario(work.resolve("root"));
        Path packet = work.resolve("packet");
        CaptureCommandTest.Run run = CaptureCommandTest.run(work, CaptureCommandTest.CLEAN,
                "capture", "--scenarios", scenario.toString(), "--output", packet.toString());
        assertEquals(0, run.exit(), run::err);
        return packet;
    }

    private static CaptureCommandTest.Run verify(Path work, Path packet, String sha, String policy,
            Path reportDir) {
        return CaptureCommandTest.run(work, CaptureCommandTest.CLEAN,
                "verify", "--packet", packet.toString(), "--packet-receipt-sha256", sha,
                "--policy", policy, "--output", reportDir.toString());
    }

    @Test
    void aCrossRouteSplitStopsConfirmationButCharacterizationRetainsIt(@TempDir Path work) throws IOException {
        Path packet = capture(work);
        String sha = tamperObservation(packet, "premium-computes", "kernel-java-static",
                text -> text.replace("\"20.5\"", "\"20.9\""));

        CaptureCommandTest.Run confirmed = verify(work, packet, sha, "kernel-route-confirmed-v1",
                work.resolve("confirmed"));
        assertEquals(0, confirmed.exit(), confirmed::err);
        assertTrue(confirmed.out().contains("REJECTED"));
        String report = Files.readString(work.resolve("confirmed/REPORT.json"));
        assertTrue(report.contains("DIVERGE"), report);
        assertTrue(report.contains("\"result\": \"divergent\""), "the divergent channel is NAMED");

        CaptureCommandTest.Run characterization = verify(work, packet, sha, "characterization-v1",
                work.resolve("characterization"));
        assertEquals(0, characterization.exit());
        assertTrue(characterization.out().contains("SATISFIED"),
                "characterization RETAINS a disagreement — it never blocks on one");
    }

    @Test
    void anUnavailableRequiredInterpreterChannelFailsConformanceClosed(@TempDir Path work) throws IOException {
        Path packet = capture(work);
        // Declare the interpreter's withoutErrors channel unavailable (and empty) — consistently.
        String sha = tamperObservation(packet, "premium-computes", "a12-dmkits-interpreter", text -> text
                .replaceFirst("\"withoutErrors\": \\{\\n      \"availability\": \"available\"",
                        "\"withoutErrors\": {\n      \"availability\": \"notExposedByRunner\""));

        CaptureCommandTest.Run conformance = verify(work, packet, sha, "dmkits-projected-conformance-v1",
                work.resolve("conformance"));
        assertEquals(0, conformance.exit(), conformance::err);
        assertTrue(conformance.out().contains("REJECTED"));
        String report = Files.readString(work.resolve("conformance/REPORT.json"));
        assertTrue(report.contains("unavailableRequired"), "an unavailable required channel fails CLOSED");

        CaptureCommandTest.Run confirmed = verify(work, packet, sha, "kernel-route-confirmed-v1",
                work.resolve("confirmed"));
        assertTrue(confirmed.out().contains("SATISFIED"),
                "the confirmed policy only REPORTS the interpreter — kernel-route agreement still holds");
    }

    @Test
    void undeclaredByteDriftRefusesVerificationOutright(@TempDir Path work) throws IOException {
        Path packet = capture(work);
        String originalSha = Digests.sha256(Files.readAllBytes(packet.resolve("RECEIPT.json")));
        // Tamper WITHOUT re-binding — the receipt no longer matches the artifact bytes.
        Path artifact = packet.resolve("observations/premium-computes/kernel-java-static.json");
        Files.writeString(artifact, Files.readString(artifact).replace("\"20.5\"", "\"20.9\""));

        CaptureCommandTest.Run run = verify(work, packet, originalSha, "characterization-v1",
                work.resolve("r"));
        assertEquals(1, run.exit());
        assertTrue(run.err().contains("drifted"), run::err);
    }
}
