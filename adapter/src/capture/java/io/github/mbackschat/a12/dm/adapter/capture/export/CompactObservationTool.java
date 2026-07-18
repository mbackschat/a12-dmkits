package io.github.mbackschat.a12.dm.adapter.capture.export;

import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.mbackschat.a12.dm.adapter.capture.dto.PolicyId;
import io.github.mbackschat.a12.dm.adapter.capture.dto.RunnerId;
import io.github.mbackschat.a12.dm.adapter.capture.json.Digests;
import io.github.mbackschat.a12.dm.adapter.capture.json.Json;
import io.github.mbackschat.a12.dm.adapter.capture.packet.CaptureRefusedException;
import io.github.mbackschat.a12.dm.adapter.capture.packet.OutputDirs;
import io.github.mbackschat.a12.dm.adapter.capture.packet.Receipt;
import io.github.mbackschat.a12.dm.adapter.capture.packet.SourceState;
import io.github.mbackschat.a12.dm.adapter.capture.policy.PolicyEngine;
import io.github.mbackschat.a12.dm.adapter.capture.policy.VerifiedPacket;
import io.github.mbackschat.a12.dm.adapter.capture.schema.ScenarioSet;

/**
 * The compact semantic-observation exporter's ONE command entry (CONFORMANCE-CORPUS-SPEC §15h) —
 * post-capture, adapter-internal, Gradle-launched ({@code :adapter:exportSemanticObservations}),
 * SEPARATE from {@code CaptureTool} and never advertised in
 * {@code a12-dmkits-capture-capabilities-v1}. It accepts ONLY the retained evidence unit — the
 * supplied receipt digests must be the frozen identities (the {@link CompactProjection.SourceIdentity}
 * invariant, refused before either tree is read) — re-verifies the packet and qualification byte
 * integrity against them, re-applies
 * {@code kernel-route-confirmed-v1} through the existing {@code PolicyEngine}, projects the
 * {@code kernel-groovy-dynamic} anchor through {@link CompactProjection}, and writes exactly
 * {@code <output>/semantic-observations.json} (canonical UTF-8, final newline, at most 256 KiB,
 * SHA-256 printed, no receipt tree). Every refusal happens before anything is written.
 */
public final class CompactObservationTool {

    public static final String OUTPUT_FILE = "semantic-observations.json";
    static final int MAX_BYTES = 256 * 1024;
    private static final List<String> OPTIONS = List.of("--packet", "--packet-receipt-sha256",
            "--qualification", "--qualification-receipt-sha256", "--output");

    /** The command environment — injectable so tests cover the refusal branches deterministically. */
    public record Env(PrintStream out, PrintStream err, SourceState.Probe source, Path repoRoot) {
    }

    private CompactObservationTool() {
    }

    public static void main(String[] args) {
        String repoRoot = System.getProperty("capture.repoRoot");
        if (repoRoot == null) {
            System.err.println("export refused: set -Dcapture.repoRoot (the Gradle task does)");
            System.exit(2);
        }
        Path root = Path.of(repoRoot);
        System.exit(run(args, new Env(System.out, System.err, SourceState.gitProbe(root), root)));
    }

    /** The one real entry: exit 0 = exported, 1 = refused (nothing written), 2 = usage. */
    public static int run(String[] args, Env env) {
        Map<String, String> options;
        try {
            options = options(args);
        } catch (IllegalArgumentException usage) {
            env.err().println("export refused: " + usage.getMessage());
            env.err().println("usage: --packet <dir> --packet-receipt-sha256 <hex>"
                    + " --qualification <dir> --qualification-receipt-sha256 <hex>"
                    + " --output <new-or-empty-dir>");
            return 2;
        }
        try {
            export(options, env);
            return 0;
        } catch (CaptureRefusedException | IllegalStateException refused) {
            env.err().println("export refused: " + refused.getMessage());
            return 1;
        }
    }

    private static void export(Map<String, String> options, Env env) {
        SourceState source = env.source().state();
        if (!source.clean()) {
            throw new CaptureRefusedException("the exporter requires a CLEAN committed a12-dmkits"
                    + " revision — the emitted source identity certifies the projection");
        }
        // The closed frozen-evidence invariant (Z5-R2): refuses any non-retained receipt identity
        // BEFORE either tree is read or verified.
        CompactProjection.SourceIdentity identity = new CompactProjection.SourceIdentity(
                source.revision(), options.get("--packet-receipt-sha256"),
                options.get("--qualification-receipt-sha256"));
        Path outputDir = Path.of(options.get("--output"));
        OutputDirs.requireNewOrEmptyOutsideCorpus(outputDir, env.repoRoot());

        Path packetDir = Path.of(options.get("--packet"));
        VerifiedPacket packet = VerifiedPacket.load(packetDir, identity.packetReceiptSha256());
        requireReceiptTree(Path.of(options.get("--qualification")),
                identity.qualificationReceiptSha256());
        requireFrozenRequest(packet.requestSha256());
        requireFrozenKernel(packetDir);
        CompactProjection.requireRouteConfirmed(
                PolicyEngine.apply(PolicyId.KERNEL_ROUTE_CONFIRMED, packet));

        Map<String, Json.Obj> anchors = new LinkedHashMap<>();
        for (ScenarioSet.ScenarioCase scenarioCase : packet.request().cases()) {
            anchors.put(scenarioCase.caseId(),
                    packet.observation(scenarioCase.caseId(), RunnerId.KERNEL_GROOVY_DYNAMIC.wire()));
        }
        Json.Obj bundle = CompactProjection.bundle(packet.request(), anchors, identity);
        String sha256 = write(Json.canonicalBytes(bundle), outputDir, env.repoRoot());
        env.out().println("exported " + outputDir.resolve(OUTPUT_FILE));
        env.out().println(OUTPUT_FILE + " sha256: " + sha256);
    }

    /** Producer check 3: the qualification directory's receipt tree verified against the exact
     *  out-of-band digest — byte integrity only, deliberately NOT a general report-verification
     *  framework (this exporter pins one already retained qualification tree). */
    static void requireReceiptTree(Path dir, String expectedSha256) {
        byte[] receiptBytes = read(dir.resolve(Receipt.FILE_NAME));
        String actual = Digests.sha256(receiptBytes);
        if (!actual.equals(expectedSha256)) {
            throw new CaptureRefusedException("qualification integrity: RECEIPT.json digest " + actual
                    + " does not match the supplied out-of-band digest " + expectedSha256);
        }
        if (!Receipt.fromJson(Json.parse(receiptBytes)).equals(Receipt.over(dir))) {
            throw new CaptureRefusedException("qualification integrity: the live inventory drifted"
                    + " from RECEIPT.json — the tree is not the one the receipt binds");
        }
    }

    /** Producer check 2 (guard 4's value half): the envelope-bound copied-request digest must be
     *  the frozen {@link CompactProjection#REQUEST_SHA256} — any changed placement, probe, case
     *  order, or input VALUE changes the copied request bytes and refuses here. */
    static void requireFrozenRequest(String requestSha256) {
        if (!CompactProjection.REQUEST_SHA256.equals(requestSha256)) {
            throw new CaptureRefusedException("the copied request (SHA-256 " + requestSha256
                    + ") is not the frozen " + CompactProjection.REQUEST_SHA256
                    + " — the exporter accepts only the retained evidence unit's exact request");
        }
    }

    /** The frozen family identity: the verified packet's kernel behavior anchor must be exactly
     *  {@link CompactProjection#KERNEL_VERSION}. */
    private static void requireFrozenKernel(Path packetDir) {
        if (Json.parse(read(packetDir.resolve("PACKET.json"))) instanceof Json.Obj envelope
                && envelope.members().get("kernel") instanceof Json.Obj kernel
                && kernel.members().get("version") instanceof Json.Str version
                && CompactProjection.KERNEL_VERSION.equals(version.value())) {
            return;
        }
        throw new CaptureRefusedException("the exporter accepts only the retained kernel behavior "
                + CompactProjection.KERNEL_VERSION + " evidence unit");
    }

    /** The caller-owned write step (guard 10): new/empty non-corpus directory, the 256 KiB compact
     *  ceiling, ONE file, no receipt tree; returns the file's SHA-256. */
    static String write(byte[] bytes, Path outputDir, Path repoRoot) {
        if (bytes.length > MAX_BYTES) {
            throw new CaptureRefusedException("the compact bundle is " + bytes.length + " bytes —"
                    + " over the deliberate " + MAX_BYTES + "-byte compact-export ceiling");
        }
        OutputDirs.requireNewOrEmptyOutsideCorpus(outputDir, repoRoot);
        try {
            Files.createDirectories(outputDir);
            Files.write(outputDir.resolve(OUTPUT_FILE), bytes);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to write " + OUTPUT_FILE, e);
        }
        return Digests.sha256(bytes);
    }

    private static Map<String, String> options(String[] args) {
        Map<String, String> options = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i += 2) {
            if (!OPTIONS.contains(args[i]) || i + 1 >= args.length) {
                throw new IllegalArgumentException("malformed option \"" + args[i]
                        + "\" — options are --name value pairs");
            }
            options.put(args[i], args[i + 1]);
        }
        for (String name : OPTIONS) {
            if (!options.containsKey(name)) {
                throw new IllegalArgumentException("missing required option " + name);
            }
        }
        return options;
    }

    private static byte[] read(Path file) {
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read " + file, e);
        }
    }
}
