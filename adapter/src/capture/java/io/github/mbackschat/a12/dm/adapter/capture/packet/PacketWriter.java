package io.github.mbackschat.a12.dm.adapter.capture.packet;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import io.github.mbackschat.a12.dm.adapter.capture.dto.ComputeObservation;
import io.github.mbackschat.a12.dm.adapter.capture.dto.RunnerId;
import io.github.mbackschat.a12.dm.adapter.capture.json.Digests;
import io.github.mbackschat.a12.dm.adapter.capture.json.Json;
import io.github.mbackschat.a12.dm.adapter.capture.legality.LegalityGate;
import io.github.mbackschat.a12.dm.adapter.capture.runner.CaseExecution;
import io.github.mbackschat.a12.dm.adapter.capture.runner.Runners;
import io.github.mbackschat.a12.dm.adapter.capture.runner.kernel.KernelArtifacts;
import io.github.mbackschat.a12.dm.adapter.capture.schema.Capabilities;
import io.github.mbackschat.a12.dm.adapter.capture.schema.ScenarioSet;
import io.github.mbackschat.a12.dm.adapter.capture.schema.ScenarioSets;
import io.github.mbackschat.a12.dm.adapter.capture.schema.SchemaIds;

/**
 * The CAPTURE orchestrator (proposal §2/§6/§7): refuse-before-mutate (dirty source in default mode, a
 * non-empty/corpus output directory), the LEGALITY gate before any runtime execution (an illegal model
 * yields a characterization artifact and its cases are never executed), fresh per-(case, runner)
 * observation, byte-for-byte copied inputs, deterministic directory-shaped output (canonical bytes, no
 * timestamps, no host metadata), the metadata leak guard on every GENERATED artifact, and a closed
 * {@code RECEIPT.json} whose SHA-256 is the out-of-band handoff value.
 *
 * <p><b>Staged finalization under a pre/post closure-identity check (audit Finding 5):</b> the complete
 * {@link ClosureIdentity} is resolved BEFORE execution, every artifact is written into a staging
 * directory, the identity is resolved AGAIN after all runners complete, and a difference — a mid-run
 * source edit, kernel-closure change, or runtime change — refuses finalization with the staging tree
 * deleted. {@code PACKET.json} (binding the verified identity) and {@code RECEIPT.json} are published
 * only after the post-run check succeeds.
 */
public final class PacketWriter {

    /** The capture tool's own version — part of the packet's compatibility identity. */
    public static final String TOOL_NAME = "a12-dmkits-capture";
    public static final String TOOL_VERSION = "1";

    private static final String STAGING = ".staging";

    private PacketWriter() {
    }

    /** The written packet: its receipt digest (the out-of-band value) and artifact count. */
    public record Written(String receiptSha256, int artifacts) {
    }

    public static Written write(ScenarioSets.Loaded loaded, CaptureOptions options) {
        ClosureIdentity before = options.identityProbe().resolve();
        refuseUnlessRunnable(options, before);
        Path out = options.outputDir();
        Path staging = out.resolve(STAGING);
        LeakGuard leakGuard = new LeakGuard(options.repoRoot().toAbsolutePath().toString(),
                out.toAbsolutePath().toString(), options.scenarioFile().toAbsolutePath().toString());

        try {
            Files.createDirectories(staging);

            // Copied inputs — exact bytes, never scanned or rewritten (consumer review governs them).
            Path request = staging.resolve("request/scenarios.json");
            Files.createDirectories(request.getParent());
            Files.write(request, loaded.scenarioBytes());
            for (Map.Entry<String, ScenarioSets.ModelBytes> model : loaded.models().entrySet()) {
                Path copy = staging.resolve("inputs/" + model.getKey());
                Files.createDirectories(copy.getParent());
                Files.write(copy, model.getValue().bytes());
            }

            // The exact capability declaration this capture ran under — copied INTO the packet so the
            // fidelity claims are self-contained (never a separately trusted checkout file).
            byte[] capabilityBytes = Json.canonicalBytes(Capabilities.emit());
            writeGenerated(staging, "capabilities/capabilities.json", capabilityBytes, leakGuard);

            // Legality BEFORE runtime — one artifact per model, provenance-recorded.
            Map<String, LegalityGate.Result> legality = new LinkedHashMap<>();
            for (Map.Entry<String, ScenarioSets.ModelBytes> model : loaded.models().entrySet()) {
                LegalityGate.Result result = LegalityGate.check(model.getKey(), model.getValue(),
                        behaviorAnchor(before).version());
                legality.put(model.getKey(), result);
                writeGenerated(staging, "legality/" + model.getKey(), Json.canonicalBytes(result.artifact()),
                        leakGuard);
            }

            // Observations — only for cases whose model passed the gate; fresh state per (case, runner).
            Map<String, List<Json>> observationRefsByCase = new LinkedHashMap<>();
            for (ScenarioSet.ScenarioCase scenarioCase : loaded.set().cases()) {
                ScenarioSets.ModelBytes model = loaded.models().get(scenarioCase.modelRef());
                if (!legality.get(scenarioCase.modelRef()).legal()) {
                    observationRefsByCase.put(scenarioCase.caseId(), List.of());
                    continue;
                }
                CaseExecution execution = CaseExecution.of(scenarioCase,
                        new String(model.bytes(), StandardCharsets.UTF_8), loaded.set().worldLocale());
                java.util.ArrayList<Json> refs = new java.util.ArrayList<>();
                for (RunnerId runner : loaded.set().requiredRunners()) {
                    ComputeObservation observation = Runners.observe(runner, execution);
                    String artifactPath = "observations/" + scenarioCase.caseId() + "/" + runner.wire() + ".json";
                    byte[] bytes = Json.canonicalBytes(ObservationJson.toJson(observation,
                            scenarioCase.modelRef(), model.sha256(), model.sha256()));
                    writeGenerated(staging, artifactPath, bytes, leakGuard);
                    refs.add(Json.Obj.of()
                            .put("runner", runner.wire())
                            .put("artifact", artifactPath)
                            .put("sha256", Digests.sha256(bytes))
                            .put("status", observation.status().wire()));
                }
                observationRefsByCase.put(scenarioCase.caseId(), refs);
            }

            // The post-run identity check: refuse finalization when ANYTHING in the closure changed
            // while the runners executed — no apparently-clean packet may span two identities.
            ClosureIdentity after = options.identityProbe().resolve();
            if (!after.equals(before)) {
                throw new CaptureRefusedException("the capture closure identity CHANGED between execution"
                        + " and finalization (source revision/tree state, resolved kernel closure, or"
                        + " runtime identity) — finalization refused, the staged artifacts were discarded,"
                        + " and no packet was produced. Re-run from a stable checkout.");
            }

            byte[] envelope = Json.canonicalBytes(
                    envelope(loaded, options, before, Digests.sha256(capabilityBytes), legality,
                            observationRefsByCase));
            writeGenerated(staging, "PACKET.json", envelope, leakGuard);

            publish(staging, out);
            Receipt receipt = Receipt.over(out);
            byte[] receiptBytes = Json.canonicalBytes(receipt.toJson());
            leakGuard.checkGenerated(Receipt.FILE_NAME, receiptBytes);
            Files.write(out.resolve(Receipt.FILE_NAME), receiptBytes);
            return new Written(Digests.sha256(receiptBytes), receipt.artifacts().size() + 1);
        } catch (IOException e) {
            discardStaging(staging);
            throw new UncheckedIOException("failed to write the packet", e);
        } catch (RuntimeException refusalOrGuard) {
            discardStaging(staging);
            throw refusalOrGuard;
        }
    }

    /** The behavior anchor out of the resolved closure (the runtime-service artifact both routes share). */
    private static KernelArtifacts.Identity behaviorAnchor(ClosureIdentity identity) {
        return identity.kernelArtifacts().stream()
                .filter(a -> a.coordinate().endsWith(":kernel-md-runtime-service"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("the resolved closure carries no"
                        + " kernel-md-runtime-service artifact — capture requires the kernel runtime it"
                        + " binds into the packet"));
    }

    /** Publish the staged tree into the caller-owned output directory (PACKET.json included), leaving no
     *  staging residue; the receipt is written LAST, over the final layout. */
    private static void publish(Path staging, Path out) throws IOException {
        try (DirectoryStream<Path> children = Files.newDirectoryStream(staging)) {
            for (Path child : children) {
                Files.move(child, out.resolve(child.getFileName()));
            }
        }
        Files.delete(staging);
    }

    private static void discardStaging(Path staging) {
        if (!Files.exists(staging)) {
            return;
        }
        try (Stream<Path> tree = Files.walk(staging)) {
            for (Path path : tree.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException cleanupFailure) {
            // The refusal itself is the primary signal; a cleanup failure leaves only the dot-staging
            // directory (never a receipt-bearing packet), so it cannot read as a clean packet.
        }
    }

    private static void refuseUnlessRunnable(CaptureOptions options, ClosureIdentity identity) {
        if (!options.development() && !identity.source().clean()) {
            throw new CaptureRefusedException("the working tree is DIRTY — default-mode capture requires a"
                    + " clean committed checkout (revision-bound evidence). For red/green or mutation work run"
                    + " with --development; a development packet records the dirty state and is PERMANENTLY"
                    + " ineligible for every portable qualification policy.");
        }
        OutputDirs.requireNewOrEmptyOutsideCorpus(options.outputDir(), options.repoRoot());
    }

    private static void writeGenerated(Path stagingRoot, String relativePath, byte[] bytes, LeakGuard leakGuard)
            throws IOException {
        leakGuard.checkGenerated(relativePath, bytes);
        Path file = stagingRoot.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.write(file, bytes);
    }

    private static Json envelope(ScenarioSets.Loaded loaded, CaptureOptions options,
            ClosureIdentity identity, String capabilitiesSha256,
            Map<String, LegalityGate.Result> legality, Map<String, List<Json>> observationRefsByCase) {
        ScenarioSet set = loaded.set();
        KernelArtifacts.Identity anchor = behaviorAnchor(identity);
        Json.Arr closure = Json.Arr.of();
        for (KernelArtifacts.Identity artifact : identity.kernelArtifacts()) {
            closure.add(Json.Obj.of()
                    .put("coordinate", artifact.coordinate())
                    .put("version", artifact.version())
                    .put("sha256", artifact.sha256()));
        }
        Json.Obj root = Json.Obj.of()
                .put("schema", SchemaIds.EVIDENCE_PACKET)
                .put("operationSchema", set.operationSchema())
                .put("capabilities", Json.Obj.of()
                        .put("schema", SchemaIds.CAPABILITIES)
                        .put("version", SchemaIds.CAPABILITIES_VERSION)
                        .put("artifact", "capabilities/capabilities.json")
                        .put("sha256", capabilitiesSha256))
                .put("kernel", Json.Obj.of()
                        .put("artifactCoordinate", anchor.coordinate())
                        .put("version", anchor.version())
                        .put("artifactSha256", anchor.sha256())
                        // The EXPLICIT closure: every resolved semantics-affecting artifact, hashed — one
                        // coordinate is never assumed to cover the whole execution (audit Finding 5).
                        .put("closure", closure))
                .put("capture", Json.Obj.of()
                        .put("tool", TOOL_NAME)
                        .put("toolVersion", TOOL_VERSION)
                        .put("sourceRevision", identity.source().revision())
                        .put("workingTree", identity.source().workingTreeWire())
                        .put("developmentMode", options.development())
                        .put("treeStateDigest", identity.source().treeStateDigest())
                        .put("runtime", Json.Obj.of()
                                .put("javaSpecificationVersion", identity.runtime().javaSpecificationVersion())
                                .put("javaVendor", identity.runtime().javaVendor())
                                .put("javaRuntimeVersion", identity.runtime().javaRuntimeVersion())
                                .put("vmName", identity.runtime().vmName()))
                        .put("closureNote", "the capture implementation and interpreter closures are"
                                + " in-repo source, bound by sourceRevision + treeStateDigest; the in-memory"
                                + " javac of the java-static route is the JDK's own compiler, bound by the"
                                + " runtime identity; this identity was resolved before execution and"
                                + " re-verified unchanged before finalization"))
                .put("scenario", Json.Obj.of()
                        .put("id", set.scenarioSetId())
                        .put("version", set.scenarioSetVersion())
                        .put("requestArtifact", "request/scenarios.json")
                        .put("requestSha256", Digests.sha256(loaded.scenarioBytes())))
                .put("world", Json.Obj.of()
                        .put("profile", set.worldProfile())
                        .put("locale", set.worldLocale())
                        .put("timezoneHandling", "runner-local-wall-clock"));

        Json.Arr runners = Json.Arr.of();
        for (RunnerId runner : set.requiredRunners()) {
            runners.add(Json.Obj.of()
                    .put("id", runner.wire())
                    .put("role", runner.role())
                    .put("route", runner.route()));
        }
        root.put("runners", runners);

        Json.Arr inputs = Json.Arr.of();
        for (Map.Entry<String, ScenarioSets.ModelBytes> model : loaded.models().entrySet()) {
            inputs.add(Json.Obj.of()
                    .put("ref", model.getKey())
                    .put("artifact", "inputs/" + model.getKey())
                    .put("suppliedSha256", model.getValue().sha256())
                    .put("adaptedSha256", model.getValue().sha256()));
        }
        root.put("inputs", inputs);

        Json.Arr legalityRefs = Json.Arr.of();
        for (Map.Entry<String, LegalityGate.Result> entry : legality.entrySet()) {
            legalityRefs.add(Json.Obj.of()
                    .put("ref", entry.getKey())
                    .put("artifact", "legality/" + entry.getKey())
                    .put("sha256", Digests.sha256(Json.canonicalBytes(entry.getValue().artifact())))
                    .put("verdict", entry.getValue().legal() ? "legal" : "illegal"));
        }
        root.put("legality", legalityRefs);

        Json.Arr cases = Json.Arr.of();
        for (ScenarioSet.ScenarioCase scenarioCase : set.cases()) {
            boolean legal = legality.get(scenarioCase.modelRef()).legal();
            Json.Obj entry = Json.Obj.of()
                    .put("caseId", scenarioCase.caseId())
                    .put("modelRef", scenarioCase.modelRef())
                    .put("legality", legal ? "legal" : "illegal")
                    .put("executed", legal);
            Json.Arr observations = Json.Arr.of();
            observationRefsByCase.get(scenarioCase.caseId()).forEach(observations::add);
            entry.put("observations", observations);
            cases.add(entry);
        }
        root.put("cases", cases);

        Json.Arr commandArgs = Json.Arr.of()
                .add(new Json.Str("capture"));
        if (options.development()) {
            commandArgs.add(new Json.Str("--development"));
        }
        commandArgs.add(new Json.Str("--scenarios")).add(new Json.Str("request/scenarios.json"))
                .add(new Json.Str("--output")).add(new Json.Str("<caller-owned-directory>"));
        root.put("command", Json.Obj.of().put("args", commandArgs));
        return root;
    }
}
