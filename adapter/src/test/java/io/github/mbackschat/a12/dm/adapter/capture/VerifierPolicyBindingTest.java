package io.github.mbackschat.a12.dm.adapter.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The REQUEST-BOUND qualification contract (proposal §1/§5; the external audit's Finding 1): verification
 * is bound to the request-declared qualification policy — a mismatching CLI policy is REFUSED (fail
 * closed, no report), never satisfied; every projection mandatory for the declared policy must be
 * predeclared in {@code comparisonProjections}; a required channel a qualifying runner does not expose
 * refuses BEFORE any runner executes; and the qualification report binds the declared
 * observation-requirement identity + the exact request digest.
 */
class VerifierPolicyBindingTest {

    @TempDir
    static Path work;

    static Path packet;
    static String receiptSha;

    @BeforeAll
    static void captureOnce() {
        Path scenario = CaptureFixtures.writeValidScenario(work.resolve("root"));
        packet = work.resolve("packet");
        CaptureCommandTest.Run run = CaptureCommandTest.run(work, CaptureCommandTest.CLEAN,
                "capture", "--scenarios", scenario.toString(), "--output", packet.toString());
        assertEquals(0, run.exit(), run::err);
        receiptSha = VerifierTest.receiptShaOf(run);
    }

    @Test
    void aMismatchingCliPolicyIsRefusedWithoutAReport(@TempDir Path reports) {
        // The request declares kernel-route-confirmed-v1: no OTHER policy may produce a report for this
        // packet — policy selection after observations are known is the anti-oracle break.
        for (String mismatching : new String[] {"characterization-v1", "dmkits-projected-conformance-v1"}) {
            Path reportDir = reports.resolve(mismatching);
            CaptureCommandTest.Run run = CaptureCommandTest.run(work, CaptureCommandTest.CLEAN,
                    "verify", "--packet", packet.toString(), "--packet-receipt-sha256", receiptSha,
                    "--policy", mismatching, "--output", reportDir.toString());
            assertEquals(1, run.exit(), mismatching + " must be refused: " + run.out());
            assertTrue(run.err().contains("kernel-route-confirmed-v1"),
                    "the refusal names the request-declared policy: " + run.err());
            assertTrue(Files.notExists(reportDir.resolve("REPORT.json")), "no report on refusal");
        }
    }

    @Test
    void theDeclaredPolicyIsSatisfiedOnTheCleanAgreeingPacket(@TempDir Path reports) throws IOException {
        CaptureCommandTest.Run run = CaptureCommandTest.run(work, CaptureCommandTest.CLEAN,
                "verify", "--packet", packet.toString(), "--packet-receipt-sha256", receiptSha,
                "--policy", "kernel-route-confirmed-v1", "--output", reports.resolve("r").toString());
        assertEquals(0, run.exit(), run::err);
        assertTrue(run.out().contains("SATISFIED"), run::out);
    }

    @Test
    void theReportBindsTheDeclaredRequirementIdentityAndRequestDigest(@TempDir Path reports) throws IOException {
        Path reportDir = reports.resolve("r");
        CaptureCommandTest.Run run = CaptureCommandTest.run(work, CaptureCommandTest.CLEAN,
                "verify", "--packet", packet.toString(), "--packet-receipt-sha256", receiptSha,
                "--policy", "kernel-route-confirmed-v1", "--output", reportDir.toString());
        assertEquals(0, run.exit(), run::err);
        String report = Files.readString(reportDir.resolve("REPORT.json"));
        assertTrue(report.contains("\"qualificationPolicy\": \"kernel-route-confirmed-v1\""),
                "the report binds the request-DECLARED policy: " + report);
        assertTrue(report.contains("\"id\": \"quote-selftest-requirement\""),
                "…the observation-requirement identity");
        assertTrue(report.contains("\"requestSha256\""), "…and the exact request digest");
        assertTrue(report.contains("\"comparisonProjections\""), "…and the predeclared projections");
    }

    @Test
    void aMandatoryProjectionMissingFromTheRequestRefusesBeforeExecution(@TempDir Path caseWork) {
        // dmkits-projected-conformance-v1 qualifies under the portable projection — declaring the policy
        // WITHOUT predeclaring that projection is the inert-contract second instance: nothing may run.
        // (The conformance-declaring base is otherwise valid; only the mandatory projection is dropped.)
        Path scenario = CaptureFixtures.writeScenario(caseWork.resolve("root"), text -> CaptureFixtures
                .declareConformance().apply(text)
                .replaceFirst(",\\s*\"compute-projection-dmkits-portable-v1\"", ""));
        for (String[] command : new String[][] {
                {"validate-scenarios", "--scenarios", scenario.toString()},
                {"capture", "--scenarios", scenario.toString(),
                        "--output", caseWork.resolve("packet").toString()}}) {
            CaptureCommandTest.Run run = CaptureCommandTest.run(caseWork, CaptureCommandTest.CLEAN, command);
            assertEquals(1, run.exit(), command[0] + " must refuse: " + run.out());
            assertTrue(run.err().contains("compute-projection-dmkits-portable-v1"),
                    command[0] + " names the missing mandatory projection: " + run.err());
        }
        assertTrue(Files.notExists(caseWork.resolve("packet")), "nothing was executed or written");
    }

    @Test
    void aRequiredChannelAQualifyingRunnerCannotExposeRefusesBeforeExecution(@TempDir Path caseWork) {
        // Conformance makes the interpreter a QUALIFYING runner; the interpreter's registry fidelity
        // declares no changedSubset — requiring that channel must fail closed before any runner executes.
        Path scenario = CaptureFixtures.writeScenario(caseWork.resolve("root"), text -> text
                .replace("\"kernel-route-confirmed-v1\"", "\"dmkits-projected-conformance-v1\"")
                .replace("\"compute-projection-kernel-route-v1\"",
                        "\"compute-projection-kernel-route-v1\", \"compute-projection-dmkits-portable-v1\""));
        CaptureCommandTest.Run run = CaptureCommandTest.run(caseWork, CaptureCommandTest.CLEAN,
                "capture", "--scenarios", scenario.toString(),
                "--output", caseWork.resolve("packet").toString());
        assertEquals(1, run.exit(), run::out);
        assertTrue(run.err().contains("changedSubset") && run.err().contains("a12-dmkits-interpreter"),
                "the refusal names the channel and the qualifying runner: " + run.err());
        assertTrue(Files.notExists(caseWork.resolve("packet")), "refused BEFORE execution — nothing written");
    }

    @Test
    void aQualifyingRunnerMissingFromRequiredRunnersRefusesBeforeExecution(@TempDir Path caseWork) {
        Path scenario = CaptureFixtures.writeScenario(caseWork.resolve("root"), text -> text
                .replace("\"kernel-groovy-dynamic\", \"kernel-java-static\", \"a12-dmkits-interpreter\"",
                        "\"kernel-groovy-dynamic\", \"a12-dmkits-interpreter\""));
        CaptureCommandTest.Run run = CaptureCommandTest.run(caseWork, CaptureCommandTest.CLEAN,
                "validate-scenarios", "--scenarios", scenario.toString());
        assertEquals(1, run.exit(), run::out);
        assertTrue(run.err().contains("kernel-java-static"),
                "the refusal names the missing qualifying runner: " + run.err());
    }
}
