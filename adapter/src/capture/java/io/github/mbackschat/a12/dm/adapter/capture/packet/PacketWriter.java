package io.github.mbackschat.a12.dm.adapter.capture.packet;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.mbackschat.a12.dm.adapter.capture.dto.ComputeObservation;
import io.github.mbackschat.a12.dm.adapter.capture.dto.RunnerId;
import io.github.mbackschat.a12.dm.adapter.capture.json.Digests;
import io.github.mbackschat.a12.dm.adapter.capture.json.Json;
import io.github.mbackschat.a12.dm.adapter.capture.legality.LegalityGate;
import io.github.mbackschat.a12.dm.adapter.capture.runner.CaseExecution;
import io.github.mbackschat.a12.dm.adapter.capture.runner.Runners;
import io.github.mbackschat.a12.dm.adapter.capture.runner.kernel.KernelArtifacts;
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
 */
public final class PacketWriter {

    /** The capture tool's own version — part of the packet's compatibility identity. */
    public static final String TOOL_NAME = "a12-dmkits-capture";
    public static final String TOOL_VERSION = "1";

    private PacketWriter() {
    }

    /** The written packet: its receipt digest (the out-of-band value) and artifact count. */
    public record Written(String receiptSha256, int artifacts) {
    }

    public static Written write(ScenarioSets.Loaded loaded, CaptureOptions options) {
        refuseUnlessRunnable(options);
        Path out = options.outputDir();
        LeakGuard leakGuard = new LeakGuard(options.repoRoot().toAbsolutePath().toString(),
                out.toAbsolutePath().toString(), options.scenarioFile().toAbsolutePath().toString());
        KernelArtifacts.Identity kernel = KernelArtifacts.resolve();

        try {
            Files.createDirectories(out);

            // Copied inputs — exact bytes, never scanned or rewritten (consumer review governs them).
            Path request = out.resolve("request/scenarios.json");
            Files.createDirectories(request.getParent());
            Files.write(request, loaded.scenarioBytes());
            for (Map.Entry<String, ScenarioSets.ModelBytes> model : loaded.models().entrySet()) {
                Path copy = out.resolve("inputs/" + model.getKey());
                Files.createDirectories(copy.getParent());
                Files.write(copy, model.getValue().bytes());
            }

            // Legality BEFORE runtime — one artifact per model, provenance-recorded.
            Map<String, LegalityGate.Result> legality = new LinkedHashMap<>();
            for (Map.Entry<String, ScenarioSets.ModelBytes> model : loaded.models().entrySet()) {
                LegalityGate.Result result = LegalityGate.check(model.getKey(), model.getValue(),
                        kernel.version());
                legality.put(model.getKey(), result);
                writeGenerated(out, "legality/" + model.getKey(), Json.canonicalBytes(result.artifact()),
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
                    writeGenerated(out, artifactPath, bytes, leakGuard);
                    refs.add(Json.Obj.of()
                            .put("runner", runner.wire())
                            .put("artifact", artifactPath)
                            .put("sha256", Digests.sha256(bytes))
                            .put("status", observation.status().wire()));
                }
                observationRefsByCase.put(scenarioCase.caseId(), refs);
            }

            byte[] envelope = Json.canonicalBytes(
                    envelope(loaded, options, kernel, legality, observationRefsByCase));
            writeGenerated(out, "PACKET.json", envelope, leakGuard);

            Receipt receipt = Receipt.over(out);
            byte[] receiptBytes = Json.canonicalBytes(receipt.toJson());
            leakGuard.checkGenerated(Receipt.FILE_NAME, receiptBytes);
            Files.write(out.resolve(Receipt.FILE_NAME), receiptBytes);
            return new Written(Digests.sha256(receiptBytes), receipt.artifacts().size() + 1);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to write the packet", e);
        }
    }

    private static void refuseUnlessRunnable(CaptureOptions options) {
        if (!options.development() && !options.sourceState().clean()) {
            throw new CaptureRefusedException("the working tree is DIRTY — default-mode capture requires a"
                    + " clean committed checkout (revision-bound evidence). For red/green or mutation work run"
                    + " with --development; a development packet records the dirty state and is PERMANENTLY"
                    + " ineligible for every portable qualification policy.");
        }
        OutputDirs.requireNewOrEmptyOutsideCorpus(options.outputDir(), options.repoRoot());
    }

    private static void writeGenerated(Path out, String relativePath, byte[] bytes, LeakGuard leakGuard)
            throws IOException {
        leakGuard.checkGenerated(relativePath, bytes);
        Path file = out.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.write(file, bytes);
    }

    private static Json envelope(ScenarioSets.Loaded loaded, CaptureOptions options,
            KernelArtifacts.Identity kernel, Map<String, LegalityGate.Result> legality,
            Map<String, List<Json>> observationRefsByCase) {
        ScenarioSet set = loaded.set();
        Json.Obj root = Json.Obj.of()
                .put("schema", SchemaIds.EVIDENCE_PACKET)
                .put("operationSchema", set.operationSchema())
                .put("capabilities", Json.Obj.of()
                        .put("schema", SchemaIds.CAPABILITIES)
                        .put("version", SchemaIds.CAPABILITIES_VERSION))
                .put("kernel", Json.Obj.of()
                        .put("artifactCoordinate", kernel.coordinate())
                        .put("version", kernel.version())
                        .put("artifactSha256", kernel.sha256()))
                .put("capture", Json.Obj.of()
                        .put("tool", TOOL_NAME)
                        .put("toolVersion", TOOL_VERSION)
                        .put("sourceRevision", options.sourceState().revision())
                        .put("workingTree", options.sourceState().workingTreeWire())
                        .put("developmentMode", options.development())
                        .put("treeStateDigest", options.sourceState().treeStateDigest())
                        .put("javaSpecificationVersion", System.getProperty("java.specification.version")))
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
