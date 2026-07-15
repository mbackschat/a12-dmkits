package io.github.mbackschat.a12.dm.adapter.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.mbackschat.a12.dm.adapter.capture.packet.LeakGuard;

/**
 * The metadata LEAK guard (proposal §2): generated packet metadata carries no absolute path, username,
 * home directory, hostname, kernel class name, or stack frame — while the COPIED consumer inputs are
 * exempt: their String values may legitimately look like paths, and the guard never scans or rewrites
 * their semantic content.
 */
class MetadataLeakGuardTest {

    @Test
    void firesOnEachLeakClassInGeneratedMetadata() {
        LeakGuard guard = new LeakGuard("/work/repo", "/work/out", "/work/scenarios.json");
        assertThrows(IllegalStateException.class,
                () -> guard.checkGenerated("PACKET.json", bytes("path /work/repo/adapter leaked")));
        assertThrows(IllegalStateException.class,
                () -> guard.checkGenerated("PACKET.json", bytes("home " + System.getProperty("user.home"))));
        assertThrows(IllegalStateException.class,
                () -> guard.checkGenerated("PACKET.json", bytes("type com.mgmtp.a12.kernel.md.rt.Internal")));
        assertThrows(IllegalStateException.class,
                () -> guard.checkGenerated("PACKET.json", bytes("\tat SomeClass.method(SomeClass.java:1)")));
    }

    @Test
    void theKernelMavenCoordinateIsSanctionedIdentityNotALeak() {
        LeakGuard guard = new LeakGuard("/work/repo", "/work/out", "/work/scenarios.json");
        guard.checkGenerated("PACKET.json",
                bytes("\"artifactCoordinate\": \"com.mgmtp.a12.kernel:kernel-md-runtime-service\""));
    }

    @Test
    void copiedConsumerInputBytesAreNeverScannedOrRewritten(@TempDir Path work) throws IOException {
        // An own-domain document-path value that LOOKS like a machine path — legitimate consumer content.
        String pathLikeValue = "/Users/alice/renewal-2026.pdf";
        Path scenario = CaptureFixtures.writeScenario(work.resolve("root"), text -> text.replace(
                "{ \"kind\": \"EMPTY\", \"path\": \"/Quote/Premium\", \"reps\": [1, 1] }",
                "{ \"kind\": \"EMPTY\", \"path\": \"/Quote/Premium\", \"reps\": [1, 1] },"
                        + " { \"kind\": \"FIELD\", \"path\": \"/Quote/InsuredName\", \"reps\": [1, 1],"
                        + " \"value\": \"" + pathLikeValue + "\" }"));
        Path packet = work.resolve("packet");
        CaptureCommandTest.Run run = CaptureCommandTest.run(work, CaptureCommandTest.CLEAN,
                "capture", "--scenarios", scenario.toString(), "--output", packet.toString());
        assertEquals(0, run.exit(), () -> "consumer input resembling a path must not refuse capture: "
                + run.err());
        assertTrue(Files.readString(packet.resolve("request/scenarios.json")).contains(pathLikeValue),
                "the copied request keeps the consumer's bytes verbatim");
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
