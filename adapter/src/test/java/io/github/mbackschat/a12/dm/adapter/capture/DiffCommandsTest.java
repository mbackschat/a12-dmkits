package io.github.mbackschat.a12.dm.adapter.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * The structural diff commands (proposal §7): packet diff distinguishes input/provenance drift from each
 * runner's observation drift (named per case + runner); report diff distinguishes profile drift from
 * qualification-result drift; identical inputs read as identical; both write only into new directories.
 */
class DiffCommandsTest {

    private static Path capture(Path work, String name) {
        Path scenario = CaptureFixtures.writeValidScenario(work.resolve(name + "-root"));
        Path packet = work.resolve(name);
        CaptureCommandTest.Run run = CaptureCommandTest.run(work, CaptureCommandTest.CLEAN,
                "capture", "--scenarios", scenario.toString(), "--output", packet.toString());
        assertEquals(0, run.exit(), run::err);
        return packet;
    }

    @Test
    void identicalPacketsDiffAsIdentical(@TempDir Path work) throws IOException {
        Path before = capture(work, "before");
        Path after = capture(work, "after");   // deterministic capture → identical contents
        CaptureCommandTest.Run run = CaptureCommandTest.run(work, CaptureCommandTest.CLEAN,
                "diff-packets", "--before", before.toString(), "--after", after.toString(),
                "--projection", "compute-projection-kernel-route-v1",
                "--output", work.resolve("diff").toString());
        assertEquals(0, run.exit(), run::err);
        assertTrue(run.out().contains("IDENTICAL"), run::out);
        String diff = Files.readString(work.resolve("diff/DIFF.json"));
        assertTrue(diff.contains("\"identical\": true"));
        assertTrue(diff.contains("\"drift\": []"));
    }

    @Test
    void observationDriftIsClassifiedPerRunnerAndCase(@TempDir Path work) throws IOException {
        Path before = capture(work, "before");
        Path after = capture(work, "after");
        // Drift one observation in AFTER (consistently re-bound, as a real recapture difference would be).
        Path artifact = after.resolve("observations/premium-computes/kernel-java-static.json");
        String oldSha = Digests.sha256(Files.readAllBytes(artifact));
        Files.writeString(artifact,
                Files.readString(artifact, StandardCharsets.UTF_8).replace("\"20.5\"", "\"20.9\""));
        String newSha = Digests.sha256(Files.readAllBytes(artifact));
        Path envelope = after.resolve("PACKET.json");
        Files.writeString(envelope, Files.readString(envelope).replace(oldSha, newSha));
        Receipt.over(after).write(after);

        CaptureCommandTest.Run run = CaptureCommandTest.run(work, CaptureCommandTest.CLEAN,
                "diff-packets", "--before", before.toString(), "--after", after.toString(),
                "--projection", "compute-projection-kernel-route-v1",
                "--output", work.resolve("diff").toString());
        assertEquals(0, run.exit(), run::err);
        String diff = Files.readString(work.resolve("diff/DIFF.json"));
        assertTrue(diff.contains("\"identical\": false"));
        assertTrue(diff.contains("\"class\": \"observation\""), diff);
        assertTrue(diff.contains("\"runner\": \"kernel-java-static\""), "observation drift names its runner");
        assertTrue(diff.contains("\"caseId\": \"premium-computes\""), "…and its case");
        assertTrue(diff.contains("\"class\": \"provenance\""), "the re-bound envelope reads as provenance drift");
        assertFalse(diff.contains("\"class\": \"input\""), "no input drifted");
    }

    @Test
    void reportDiffSeparatesProfileFromQualificationResultDrift(@TempDir Path work) throws IOException {
        // Verification is bound to each packet's DECLARED policy, so differing profiles come from two
        // packets declaring different policies — each verified under its own declaration.
        Path confirmedPacket = capture(work, "confirmed-packet");
        Path characterizationScenario = CaptureFixtures.writeScenario(work.resolve("characterization-root"),
                CaptureFixtures.declareCharacterization());
        Path characterizationPacket = work.resolve("characterization-packet");
        assertEquals(0, CaptureCommandTest.run(work, CaptureCommandTest.CLEAN,
                "capture", "--scenarios", characterizationScenario.toString(),
                "--output", characterizationPacket.toString()).exit());

        Path satisfied = work.resolve("report-satisfied");
        assertEquals(0, CaptureCommandTest.run(work, CaptureCommandTest.CLEAN,
                "verify", "--packet", characterizationPacket.toString(), "--packet-receipt-sha256",
                Digests.sha256(Files.readAllBytes(characterizationPacket.resolve("RECEIPT.json"))),
                "--policy", "characterization-v1", "--output", satisfied.toString()).exit());
        Path conformance = work.resolve("report-confirmed");
        assertEquals(0, CaptureCommandTest.run(work, CaptureCommandTest.CLEAN,
                "verify", "--packet", confirmedPacket.toString(), "--packet-receipt-sha256",
                Digests.sha256(Files.readAllBytes(confirmedPacket.resolve("RECEIPT.json"))),
                "--policy", "kernel-route-confirmed-v1", "--output", conformance.toString()).exit());

        CaptureCommandTest.Run run = CaptureCommandTest.run(work, CaptureCommandTest.CLEAN,
                "diff-reports", "--before", satisfied.toString(), "--after", conformance.toString(),
                "--output", work.resolve("diff").toString());
        assertEquals(0, run.exit(), run::err);
        String diff = Files.readString(work.resolve("diff/DIFF.json"));
        assertTrue(diff.contains("\"class\": \"policy-profile\""), "different policies → profile drift");
        assertTrue(diff.contains("\"class\": \"qualification-result\""), diff);
    }
}
