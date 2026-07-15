package io.github.mbackschat.a12.dm.adapter.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The VERIFIER (proposal §5): named policies over an immutable, receipt-bound packet — reports are
 * separate digest-bound artifacts, verification never writes into the packet, integrity failures refuse
 * before any policy runs, and a development packet is rejected by every portable policy BEFORE semantic
 * comparison.
 */
class VerifierTest {

    @TempDir
    static Path work;

    static Path packet;
    static String receiptSha;
    static Map<String, byte[]> packetBytesBefore;

    @BeforeAll
    static void captureOnce() throws IOException {
        Path scenario = CaptureFixtures.writeValidScenario(work.resolve("root"));
        packet = work.resolve("packet");
        CaptureCommandTest.Run run = CaptureCommandTest.run(work, CaptureCommandTest.CLEAN,
                "capture", "--scenarios", scenario.toString(), "--output", packet.toString());
        assertEquals(0, run.exit(), run::err);
        receiptSha = receiptShaOf(run);
        packetBytesBefore = snapshot(packet);
    }

    static String receiptShaOf(CaptureCommandTest.Run run) {
        return run.out().lines().filter(l -> l.contains("RECEIPT.json sha256: "))
                .findFirst().orElseThrow().replaceAll(".*RECEIPT.json sha256: ", "");
    }

    static Map<String, byte[]> snapshot(Path dir) throws IOException {
        Map<String, byte[]> bytes = new LinkedHashMap<>();
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path file : walk.filter(Files::isRegularFile).sorted().toList()) {
                bytes.put(dir.relativize(file).toString(), Files.readAllBytes(file));
            }
        }
        return bytes;
    }

    private static CaptureCommandTest.Run verify(String policy, Path reportDir) {
        return verify(policy, reportDir, receiptSha);
    }

    private static CaptureCommandTest.Run verify(String policy, Path reportDir, String sha) {
        return CaptureCommandTest.run(work, CaptureCommandTest.CLEAN,
                "verify", "--packet", packet.toString(), "--packet-receipt-sha256", sha,
                "--policy", policy, "--output", reportDir.toString());
    }

    @Test
    void everyPolicyIsSatisfiedOnTheCleanAgreeingPacket(@TempDir Path reports) throws IOException {
        for (String policy : new String[] {"characterization-v1", "kernel-route-confirmed-v1",
                "dmkits-projected-conformance-v1"}) {
            CaptureCommandTest.Run run = verify(policy, reports.resolve(policy));
            assertEquals(0, run.exit(), run::err);
            assertTrue(run.out().contains("SATISFIED"), policy + ": " + run.out());
            String report = Files.readString(reports.resolve(policy).resolve("REPORT.json"));
            assertTrue(report.contains("\"receiptSha256\": \"" + receiptSha + "\""),
                    "the report digest-binds the exact upstream receipt");
            assertTrue(report.contains("\"profileSha256\""), "…and the pinned profile definition");
            assertTrue(Files.isRegularFile(reports.resolve(policy).resolve("PROFILE.json")));
            assertTrue(Files.isRegularFile(reports.resolve(policy).resolve("RECEIPT.json")));
        }
    }

    @Test
    void comparisonsNameRunnersProjectionAndFieldsNeverAGlobalAgrees(@TempDir Path reports) throws IOException {
        assertEquals(0, verify("kernel-route-confirmed-v1", reports.resolve("r")).exit());
        String report = Files.readString(reports.resolve("r").resolve("REPORT.json"));
        assertTrue(report.contains("\"runnerA\": \"kernel-groovy-dynamic\""));
        assertTrue(report.contains("\"runnerB\": \"kernel-java-static\""));
        assertTrue(report.contains("\"projection\": \"compute-projection-kernel-route-v1\""));
        assertTrue(report.contains("\"includedFields\""));
        assertTrue(report.contains("\"unavailableFields\""));
        assertTrue(report.contains("\"runnerB\": \"a12-dmkits-interpreter\""),
                "interpreter agreement is REPORTED under the confirmed policy");
        assertTrue(!report.contains("\"agrees\""), "no global agrees Boolean exists");
    }

    @Test
    void verificationNeverWritesIntoThePacket(@TempDir Path reports) throws IOException {
        assertEquals(0, verify("characterization-v1", reports.resolve("r")).exit());
        assertEquals(packetBytesBefore.keySet(), snapshot(packet).keySet(), "no artifact added/removed");
        for (Map.Entry<String, byte[]> entry : snapshot(packet).entrySet()) {
            assertTrue(java.util.Arrays.equals(packetBytesBefore.get(entry.getKey()), entry.getValue()),
                    "packet byte drift in " + entry.getKey());
        }
    }

    @Test
    void aWrongReceiptDigestRefusesBeforeAnyPolicy(@TempDir Path reports) {
        CaptureCommandTest.Run run = verify("characterization-v1", reports.resolve("r"), "0".repeat(64));
        assertEquals(1, run.exit());
        assertTrue(run.err().contains("does not match the supplied out-of-band digest"), run::err);
        assertTrue(Files.notExists(reports.resolve("r").resolve("REPORT.json")), "no report on refusal");
    }

    @Test
    void aDevelopmentPacketIsRejectedByEveryPortablePolicyBeforeComparison(@TempDir Path devWork)
            throws IOException {
        Path scenario = CaptureFixtures.writeValidScenario(devWork.resolve("root"));
        Path devPacket = devWork.resolve("packet");
        CaptureCommandTest.Run capture = CaptureCommandTest.run(devWork, CaptureCommandTest.DIRTY,
                "capture", "--development", "--scenarios", scenario.toString(),
                "--output", devPacket.toString());
        assertEquals(0, capture.exit(), capture::err);
        String devSha = receiptShaOf(capture);

        for (String policy : new String[] {"characterization-v1", "kernel-route-confirmed-v1",
                "dmkits-projected-conformance-v1"}) {
            Path reportDir = devWork.resolve("report-" + policy);
            CaptureCommandTest.Run run = CaptureCommandTest.run(devWork, CaptureCommandTest.CLEAN,
                    "verify", "--packet", devPacket.toString(), "--packet-receipt-sha256", devSha,
                    "--policy", policy, "--output", reportDir.toString());
            assertEquals(0, run.exit(), run::err);
            assertTrue(run.out().contains("REJECTED"), policy + " must reject a development packet");
            String report = Files.readString(reportDir.resolve("REPORT.json"));
            assertTrue(report.contains("PERMANENTLY ineligible"), report);
            assertTrue(report.contains("\"comparisons\": []"),
                    "rejected BEFORE semantic comparison — no comparisons ran");
        }
    }

    @Test
    void anUnknownPolicyIsAUsageError(@TempDir Path reports) {
        CaptureCommandTest.Run run = verify("promotion-v1", reports.resolve("r"));
        assertEquals(2, run.exit());
        assertTrue(run.err().contains("unknown policy"), run::err);
    }
}
