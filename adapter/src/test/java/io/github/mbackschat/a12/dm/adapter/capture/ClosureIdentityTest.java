package io.github.mbackschat.a12.dm.adapter.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.mbackschat.a12.dm.adapter.capture.packet.ClosureIdentity;
import io.github.mbackschat.a12.dm.adapter.capture.packet.SourceState;
import io.github.mbackschat.a12.dm.adapter.capture.runner.kernel.KernelArtifacts;

/**
 * The pre/post closure-identity gate (the external audit's Finding 5): the complete identity — source
 * revision + tree state, the resolved kernel closure, the runtime — is resolved BEFORE execution and
 * AGAIN before finalization; a mid-run change refuses finalization and no apparently-clean packet is
 * produced ({@code PACKET.json}/{@code RECEIPT.json} are published only after the post-run check). The
 * envelope binds the explicit justified closure, never one coordinate for the whole execution.
 */
class ClosureIdentityTest {

    @Test
    void anIdentityChangeBetweenExecutionAndFinalizationRefusesWithNoPacket(@TempDir Path work)
            throws IOException {
        Path scenario = CaptureFixtures.writeValidScenario(work.resolve("root"));
        Path packet = work.resolve("packet");

        // The injectable mid-run control: the first resolve sees the clean pre-execution identity, every
        // later resolve a DIFFERENT tree state — exactly a source edit while the runners execute.
        AtomicInteger resolves = new AtomicInteger();
        ClosureIdentity.Probe unstable = () -> new ClosureIdentity(
                resolves.getAndIncrement() == 0
                        ? CaptureCommandTest.CLEAN
                        : new SourceState("f".repeat(40), true, "0".repeat(64)),
                KernelArtifacts.resolveAll(), ClosureIdentity.RuntimeIdentity.current());

        CaptureCommandTest.Run run = CaptureCommandTest.run(work, unstable,
                "capture", "--scenarios", scenario.toString(), "--output", packet.toString());
        assertEquals(1, run.exit(), run::out);
        assertTrue(run.err().contains("CHANGED between execution and finalization"), run::err);
        assertTrue(resolves.get() >= 2, "the identity is resolved before AND after execution");

        assertTrue(Files.notExists(packet.resolve("PACKET.json"))
                        && Files.notExists(packet.resolve("RECEIPT.json")),
                "no envelope or receipt is ever published on a refused finalization");
        if (Files.exists(packet)) {
            try (Stream<Path> files = Files.walk(packet)) {
                assertTrue(files.noneMatch(Files::isRegularFile),
                        "the staged artifacts are discarded — no apparently-clean packet remains");
            }
        }
    }

    @Test
    void theEnvelopeBindsTheExplicitJustifiedClosure(@TempDir Path work) throws IOException {
        Path scenario = CaptureFixtures.writeValidScenario(work.resolve("root"));
        Path packet = work.resolve("packet");
        CaptureCommandTest.Run run = CaptureCommandTest.run(work, CaptureCommandTest.CLEAN,
                "capture", "--scenarios", scenario.toString(), "--output", packet.toString());
        assertEquals(0, run.exit(), run::err);

        String envelope = Files.readString(packet.resolve("PACKET.json"));
        assertTrue(envelope.contains("\"closure\": ["), "the kernel closure is an explicit artifact list");
        assertTrue(envelope.contains("com.mgmtp.a12.kernel:kernel-md-runtime-service"),
                "…including the behavior anchor");
        assertTrue(envelope.contains("org.codehaus.groovy:groovy"),
                "…and the Groovy runtime the dynamic route executes through");
        assertTrue(envelope.contains("\"javaSpecificationVersion\"")
                        && envelope.contains("\"javaRuntimeVersion\"")
                        && envelope.contains("\"javaVendor\"")
                        && envelope.contains("\"vmName\""),
                "the runtime identity is bound (it also binds the in-memory javac)");
        assertTrue(envelope.contains("re-verified unchanged before finalization"),
                "the closure justification is recorded, never silently assumed");

        // The scan is genuinely broader than one coordinate.
        long closureEntries = KernelArtifacts.resolveAll().size();
        assertTrue(closureEntries > 10,
                "the resolved closure covers the kernel groups, not one jar (found " + closureEntries + ")");
    }
}
