package io.github.mbackschat.a12.dm.adapter.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * LEGALITY BEFORE RUNTIME (proposal §6): an illegal authored model NEVER reaches runtime execution — its
 * capture yields a characterization packet (the legality artifact records the rejection with both digests
 * bound), the case is marked non-executed, and no observation artifact exists. The gate is part of the
 * evidence claim, not an implementation precondition.
 */
class LegalityGateTest {

    @Test
    void anIllegalModelIsCharacterizedNeverExecuted(@TempDir Path work) throws IOException {
        Path root = work.resolve("root");
        String illegalSha = CaptureFixtures.placeModel(root, "capture/models/quote-illegal.json",
                "quote-illegal.json");
        Path scenario = CaptureFixtures.writeScenario(root, text -> text
                .replace("models/quote-premium.json", "models/quote-illegal.json")
                .replaceFirst("\"sha256\": \"[0-9a-f]{64}\"", "\"sha256\": \"" + illegalSha + "\""));
        Path packet = work.resolve("packet");

        CaptureCommandTest.Run run = CaptureCommandTest.run(work, CaptureCommandTest.CLEAN,
                "capture", "--scenarios", scenario.toString(), "--output", packet.toString());
        assertEquals(0, run.exit(), run::err);

        String legality = Files.readString(packet.resolve("legality/models/quote-illegal.json"));
        assertTrue(legality.contains("\"verdict\": \"illegal\""), legality);
        assertTrue(legality.contains("\"suppliedSha256\": \"" + illegalSha + "\""),
                "the gate binds the exact supplied bytes");
        assertTrue(legality.contains("\"adaptedSha256\": \"" + illegalSha + "\""),
                "…and the adapted digest (v1 adapts nothing — explicitly equal)");

        String envelope = Files.readString(packet.resolve("PACKET.json"));
        assertTrue(envelope.contains("\"legality\": \"illegal\""));
        assertTrue(envelope.contains("\"executed\": false"));
        assertTrue(Files.notExists(packet.resolve("observations")),
                "an illegal model must not be executed as runtime evidence — no observation artifacts");
    }

    @Test
    void aLegalModelRecordsALegalVerdictWithBoundDigests(@TempDir Path work) throws IOException {
        Path scenario = CaptureFixtures.writeValidScenario(work.resolve("root"));
        Path packet = work.resolve("packet");
        assertEquals(0, CaptureCommandTest.run(work, CaptureCommandTest.CLEAN,
                "capture", "--scenarios", scenario.toString(), "--output", packet.toString()).exit());
        String legality = Files.readString(packet.resolve("legality/models/quote-premium.json"));
        assertTrue(legality.contains("\"verdict\": \"legal\""), legality);
        assertTrue(legality.contains("\"runner\": \"kernel-checkConsistency\""));
        assertTrue(Files.isDirectory(packet.resolve("observations")), "legal cases execute");
    }
}
