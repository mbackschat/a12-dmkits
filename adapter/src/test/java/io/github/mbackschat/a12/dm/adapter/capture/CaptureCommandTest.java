package io.github.mbackschat.a12.dm.adapter.capture;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.mbackschat.a12.dm.adapter.capture.cli.CaptureTool;
import io.github.mbackschat.a12.dm.adapter.capture.json.Digests;
import io.github.mbackschat.a12.dm.adapter.capture.json.Json;
import io.github.mbackschat.a12.dm.adapter.capture.packet.ClosureIdentity;
import io.github.mbackschat.a12.dm.adapter.capture.packet.SourceState;
import io.github.mbackschat.a12.dm.adapter.capture.runner.kernel.KernelArtifacts;

/**
 * The REAL command surface (proposal §7), driven through the one entry the Gradle tasks call: refusals
 * before any mutation (dirty source in default mode, non-empty output, the committed-corpus tree), the
 * caller-owned-output contract, the packet's directory shape, and the printed out-of-band receipt digest.
 * The source-state probe is the injectable seam, so BOTH refusal branches are covered deterministically.
 */
class CaptureCommandTest {

    static final SourceState CLEAN = new SourceState("f".repeat(40), true, "d".repeat(64));
    static final SourceState DIRTY = new SourceState("f".repeat(40), false, "e".repeat(64));

    record Run(int exit, String out, String err) {
    }

    /** A stable test identity: the injected source state + the REAL kernel closure and runtime of this
     *  JVM (deterministic within a run, so the pre/post identity check passes). */
    static ClosureIdentity.Probe probeOf(SourceState state) {
        return () -> new ClosureIdentity(state, KernelArtifacts.resolveAll(),
                ClosureIdentity.RuntimeIdentity.current());
    }

    static Run run(Path repoRoot, SourceState state, String... args) {
        return run(repoRoot, probeOf(state), args);
    }

    static Run run(Path repoRoot, ClosureIdentity.Probe probe, String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exit = CaptureTool.run(args, new CaptureTool.Env(
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8),
                probe, repoRoot));
        return new Run(exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    @Test
    void captureWritesTheDirectoryShapedPacketAndPrintsTheReceiptDigest(@TempDir Path work) throws IOException {
        Path scenario = CaptureFixtures.writeValidScenario(work.resolve("root"));
        Path packet = work.resolve("packet");
        Run run = run(work, CLEAN, "capture", "--scenarios", scenario.toString(),
                "--output", packet.toString());
        assertEquals(0, run.exit(), run::err);

        assertTrue(Files.isRegularFile(packet.resolve("PACKET.json")));
        assertTrue(Files.isRegularFile(packet.resolve("RECEIPT.json")));
        assertTrue(Files.isRegularFile(packet.resolve("request/scenarios.json")));
        assertTrue(Files.isRegularFile(packet.resolve("inputs/models/quote-premium.json")));
        assertTrue(Files.isRegularFile(packet.resolve("legality/models/quote-premium.json")));
        for (String runner : new String[] {"kernel-groovy-dynamic", "kernel-java-static",
                "a12-dmkits-interpreter"}) {
            assertTrue(Files.isRegularFile(
                    packet.resolve("observations/premium-computes/" + runner + ".json")), runner);
        }
        assertArrayEquals(Files.readAllBytes(scenario), Files.readAllBytes(packet.resolve("request/scenarios.json")),
                "the request is copied byte-for-byte");

        String printed = run.out().lines().filter(l -> l.startsWith("RECEIPT.json sha256: "))
                .findFirst().orElseThrow().substring("RECEIPT.json sha256: ".length());
        assertEquals(Digests.sha256(Files.readAllBytes(packet.resolve("RECEIPT.json"))), printed,
                "the printed out-of-band digest IS the receipt file's digest");

        String envelope = Files.readString(packet.resolve("PACKET.json"));
        assertTrue(envelope.contains("\"workingTree\": \"clean\""));
        assertTrue(envelope.contains("\"developmentMode\": false"));
        Json.parse(Files.readAllBytes(packet.resolve("PACKET.json")));   // strict-parses
    }

    @Test
    void defaultModeRefusesADirtyTreeNamingDevelopmentMode(@TempDir Path work) {
        Path scenario = CaptureFixtures.writeValidScenario(work.resolve("root"));
        Run run = run(work, DIRTY, "capture", "--scenarios", scenario.toString(),
                "--output", work.resolve("packet").toString());
        assertEquals(1, run.exit());
        assertTrue(run.err().contains("--development"), run::err);
        assertTrue(Files.notExists(work.resolve("packet")), "refusal mutates nothing");
    }

    @Test
    void developmentModeCapturesOnADirtyTreeAndRecordsIt(@TempDir Path work) throws IOException {
        Path scenario = CaptureFixtures.writeValidScenario(work.resolve("root"));
        Path packet = work.resolve("packet");
        Run run = run(work, DIRTY, "capture", "--development", "--scenarios", scenario.toString(),
                "--output", packet.toString());
        assertEquals(0, run.exit(), run::err);
        assertTrue(run.out().contains("permanently"), "the ineligibility is announced");
        String envelope = Files.readString(packet.resolve("PACKET.json"));
        assertTrue(envelope.contains("\"workingTree\": \"dirty\""));
        assertTrue(envelope.contains("\"developmentMode\": true"));
    }

    @Test
    void refusesANonEmptyOutputDirectory(@TempDir Path work) throws IOException {
        Path scenario = CaptureFixtures.writeValidScenario(work.resolve("root"));
        Path packet = work.resolve("packet");
        Files.createDirectories(packet);
        Files.writeString(packet.resolve("existing.txt"), "already here");
        Run run = run(work, CLEAN, "capture", "--scenarios", scenario.toString(),
                "--output", packet.toString());
        assertEquals(1, run.exit());
        assertTrue(run.err().contains("NOT empty"), run::err);
    }

    @Test
    void refusesTheCommittedCorpusTree(@TempDir Path work) throws IOException {
        Path scenario = CaptureFixtures.writeValidScenario(work.resolve("root"));
        Files.createDirectories(work.resolve("corpus"));
        Run run = run(work, CLEAN, "capture", "--scenarios", scenario.toString(),
                "--output", work.resolve("corpus/packet").toString());
        assertEquals(1, run.exit());
        assertTrue(run.err().contains("corpus"), run::err);
        assertTrue(Files.notExists(work.resolve("corpus/packet")));
    }

    @Test
    void validateScenariosAcceptsWithoutWritingAnything(@TempDir Path work) {
        Path scenario = CaptureFixtures.writeValidScenario(work.resolve("root"));
        Run run = run(work, DIRTY, "validate-scenarios", "--scenarios", scenario.toString());
        assertEquals(0, run.exit(), run::err);
        assertTrue(run.out().contains("ACCEPTED"));
        assertTrue(run.out().contains("no engine ran"));
    }

    @Test
    void unknownCommandsAndMissingOptionsAreUsageErrors(@TempDir Path work) {
        assertEquals(2, run(work, CLEAN, "promote-corpus").exit());
        assertEquals(2, run(work, CLEAN, "capture", "--scenarios", "x").exit(), "missing --output");
        assertEquals(2, run(work, CLEAN).exit());
    }

    @Test
    void capabilitiesPrintsTheGoldenBytes(@TempDir Path work) {
        Run run = run(work, DIRTY, "capabilities");
        assertEquals(0, run.exit());
        assertEquals(new String(CaptureFixtures.resourceBytes("capture/capabilities-v1.json"),
                StandardCharsets.UTF_8), run.out());
    }
}
