package io.github.mbackschat.a12.dm.adapter.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.mbackschat.a12.dm.adapter.capture.json.Json;

/**
 * The exact consumed-document binding (the external audit's Finding 3): every SUCCESS observation binds
 * the case/placement document the runner ACTUALLY consumed — an identity computed at the runner's own
 * document-building boundary (recorded as each placement is applied to the engine), never an expected
 * digest passed through. A placement changed or dropped during placement-to-document adaptation therefore
 * changes the consumed identity and breaks the supplied==consumed equality this test pins (v1 adapts
 * nothing, so the identities must be equal).
 */
class ConsumedDocumentBindingTest {

    private static Json.Obj member(Json.Obj obj, String key) {
        return (Json.Obj) obj.members().get(key);
    }

    @Test
    void everySuccessObservationBindsTheConsumedPlacementIdentity(@TempDir Path work) throws IOException {
        Path scenario = CaptureFixtures.writeValidScenario(work.resolve("root"));
        Path packet = work.resolve("packet");
        CaptureCommandTest.Run run = CaptureCommandTest.run(work, CaptureCommandTest.CLEAN,
                "capture", "--scenarios", scenario.toString(), "--output", packet.toString());
        assertEquals(0, run.exit(), run::err);

        String expectedSha = null;
        for (String runner : new String[] {"kernel-groovy-dynamic", "kernel-java-static",
                "a12-dmkits-interpreter"}) {
            Json.Obj observation = (Json.Obj) Json.parse(Files.readAllBytes(
                    packet.resolve("observations/premium-computes/" + runner + ".json")));
            Json consumed = observation.members().get("consumedDocument");
            assertTrue(consumed instanceof Json.Obj,
                    runner + ": a success observation binds the consumed document identity");
            Json.Obj obj = (Json.Obj) consumed;
            assertEquals(3L, ((Json.Int) obj.members().get("placements")).value(),
                    runner + ": the fixture case applies exactly its three placements");
            String sha = ((Json.Str) obj.members().get("canonicalSha256")).value();
            assertEquals(64, sha.length(), runner + ": a SHA-256 canonical placement digest");
            if (expectedSha == null) {
                expectedSha = sha;
            } else {
                assertEquals(expectedSha, sha, runner + ": every runner consumed the SAME document —"
                        + " the identity is computed at each runner's own boundary, so equality is evidence,"
                        + " not pass-through");
            }
        }
    }

    @Test
    void theConsumedIdentitySeparatesEmptyFromAbsentAndValueFromValue(@TempDir Path work) throws IOException {
        // Two placement streams differing only in an EMPTY placement (present-but-valueless vs absent)
        // must yield DIFFERENT consumed identities — the canonical form covers kind, path, reps, value.
        Path scenarioWithEmpty = CaptureFixtures.writeValidScenario(work.resolve("root-a"));
        Path scenarioWithout = CaptureFixtures.writeScenario(work.resolve("root-b"), text -> text
                .replaceFirst(",\\s*\\{ \"kind\": \"EMPTY\", \"path\": \"/Quote/Premium\", \"reps\": \\[1, 1\\] \\}", ""));
        Path packetA = work.resolve("packet-a");
        Path packetB = work.resolve("packet-b");
        assertEquals(0, CaptureCommandTest.run(work, CaptureCommandTest.CLEAN, "capture",
                "--scenarios", scenarioWithEmpty.toString(), "--output", packetA.toString()).exit());
        assertEquals(0, CaptureCommandTest.run(work, CaptureCommandTest.CLEAN, "capture",
                "--scenarios", scenarioWithout.toString(), "--output", packetB.toString()).exit());

        String shaA = consumedSha(packetA);
        String shaB = consumedSha(packetB);
        assertTrue(!shaA.equals(shaB), "dropping the EMPTY placement must change the consumed identity"
                + " (a placement dropped during adaptation is exactly what this binding detects)");
    }

    private static String consumedSha(Path packet) throws IOException {
        Json.Obj observation = (Json.Obj) Json.parse(Files.readAllBytes(
                packet.resolve("observations/premium-computes/kernel-groovy-dynamic.json")));
        return ((Json.Str) member(observation, "consumedDocument").members().get("canonicalSha256")).value();
    }
}
