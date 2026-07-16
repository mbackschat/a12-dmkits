package io.github.mbackschat.a12.dm.adapter.capture;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.mbackschat.a12.dm.adapter.capture.json.Digests;

/**
 * The packet is SELF-CONTAINED for capability and fidelity claims (the external audit's Finding 4): the
 * exact canonical capabilities artifact used for preflight is COPIED into the packet, inventoried by the
 * receipt, and digest-referenced from the envelope — a consumer never has to combine an unbound packet
 * with a separately trusted checkout file to know a runner's declared fidelity.
 */
class CapabilitiesBindingTest {

    @Test
    void thePacketCarriesTheCanonicalCapabilitiesArtifactDigestBound(@TempDir Path work) throws IOException {
        Path scenario = CaptureFixtures.writeValidScenario(work.resolve("root"));
        Path packet = work.resolve("packet");
        CaptureCommandTest.Run run = CaptureCommandTest.run(work, CaptureCommandTest.CLEAN,
                "capture", "--scenarios", scenario.toString(), "--output", packet.toString());
        assertEquals(0, run.exit(), run::err);

        Path copy = packet.resolve("capabilities/capabilities.json");
        assertTrue(Files.isRegularFile(copy), "the capabilities artifact is copied into the packet");
        byte[] bytes = Files.readAllBytes(copy);
        assertArrayEquals(CaptureFixtures.resourceBytes("capture/capabilities-v1.json"), bytes,
                "…byte-equal to the tool's canonical (golden-locked) capabilities output");

        String envelope = Files.readString(packet.resolve("PACKET.json"));
        assertTrue(envelope.contains("\"artifact\": \"capabilities/capabilities.json\""),
                "the envelope references the copy");
        assertTrue(envelope.contains("\"sha256\": \"" + Digests.sha256(bytes) + "\""),
                "…with its exact digest");

        String receipt = Files.readString(packet.resolve("RECEIPT.json"));
        assertTrue(receipt.contains("\"path\": \"capabilities/capabilities.json\""),
                "the receipt inventories the capabilities artifact");
        assertTrue(receipt.contains("\"capability-declaration\""), "…under its closed role");
    }
}
