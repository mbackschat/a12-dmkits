package io.github.mbackschat.a12.dm.adapter.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import io.github.mbackschat.a12.dm.adapter.capture.json.Digests;
import io.github.mbackschat.a12.dm.adapter.capture.json.Json;
import io.github.mbackschat.a12.dm.adapter.capture.packet.ArtifactRole;
import io.github.mbackschat.a12.dm.adapter.capture.packet.Receipt;

/**
 * {@code RECEIPT.json} ({@code capture-receipt-v1}, proposal §2): a CLOSED sorted inventory — every packet
 * file including {@code PACKET.json}, itself excluded (no self-hash cycle), relative path + role + byte
 * count + SHA-256 each; symlinks and unclassifiable strays refuse the build; the receipt's own digest is
 * the out-of-band handoff value.
 */
class ReceiptTest {

    private static void write(Path dir, String relative, String content) throws IOException {
        Path file = dir.resolve(relative);
        Files.createDirectories(file.getParent() == null ? dir : file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private static Path packetDir(Path dir) throws IOException {
        write(dir, "PACKET.json", "{}\n");
        write(dir, "request/scenarios.json", "{\"schema\": \"x\"}\n");
        write(dir, "inputs/models/quote-premium.json", "{\"header\": {}}\n");
        write(dir, "observations/premium-computes/kernel-groovy-dynamic.json", "{}\n");
        return dir;
    }

    @Test
    void inventoriesEveryArtifactSortedAndSelfExcluded(@TempDir Path dir) throws IOException {
        Receipt receipt = Receipt.over(packetDir(dir));
        assertEquals(List.of("PACKET.json", "inputs/models/quote-premium.json",
                        "observations/premium-computes/kernel-groovy-dynamic.json", "request/scenarios.json"),
                receipt.artifacts().stream().map(Receipt.Artifact::path).toList(),
                "sorted by path; RECEIPT.json never inventories itself");
        Receipt.Artifact packet = receipt.artifacts().get(0);
        assertEquals(ArtifactRole.PACKET_ENVELOPE, packet.role());
        assertEquals(3, packet.bytes());
        assertEquals(Digests.sha256("{}\n".getBytes(StandardCharsets.UTF_8)), packet.sha256());
    }

    @Test
    void writeReturnsTheOutOfBandDigestAndRoundTrips(@TempDir Path dir) throws IOException {
        Receipt receipt = Receipt.over(packetDir(dir));
        String handoff = receipt.write(dir);
        byte[] written = Files.readAllBytes(dir.resolve("RECEIPT.json"));
        assertEquals(Digests.sha256(written), handoff, "the printed digest IS the receipt file's digest");
        assertEquals(receipt, Receipt.fromJson(Json.parse(written)), "receipt round-trips losslessly");
        assertEquals(receipt, Receipt.over(dir), "re-inventorying after the write is stable (self-excluded)");
    }

    @Test
    void refusesAnUnclassifiableStrayFile(@TempDir Path dir) throws IOException {
        packetDir(dir);
        write(dir, "notes.txt", "scratch");
        IllegalStateException refusal = assertThrows(IllegalStateException.class, () -> Receipt.over(dir));
        assertTrue(refusal.getMessage().contains("notes.txt"), refusal::getMessage);
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void refusesASymlink(@TempDir Path dir) throws IOException {
        packetDir(dir);
        Files.createSymbolicLink(dir.resolve("inputs/models/link.json"),
                dir.resolve("inputs/models/quote-premium.json"));
        assertThrows(IllegalStateException.class, () -> Receipt.over(dir));
    }
}
