package io.github.mbackschat.a12.dm.adapter.capture.policy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.mbackschat.a12.dm.adapter.capture.dto.ConsumedDocument;
import io.github.mbackschat.a12.dm.adapter.capture.dto.RunnerId;
import io.github.mbackschat.a12.dm.adapter.capture.json.Digests;
import io.github.mbackschat.a12.dm.adapter.capture.json.Json;
import io.github.mbackschat.a12.dm.adapter.capture.packet.CaptureRefusedException;
import io.github.mbackschat.a12.dm.adapter.capture.packet.Receipt;
import io.github.mbackschat.a12.dm.adapter.capture.schema.Capabilities;
import io.github.mbackschat.a12.dm.adapter.capture.schema.CaseDocumentIdentity;
import io.github.mbackschat.a12.dm.adapter.capture.schema.ScenarioDecoder;
import io.github.mbackschat.a12.dm.adapter.capture.schema.ScenarioRejectedException;
import io.github.mbackschat.a12.dm.adapter.capture.schema.ScenarioSet;
import io.github.mbackschat.a12.dm.adapter.capture.schema.SchemaIds;

/**
 * The ONE closed RELATIONAL packet validator (the external audit's Finding 2): byte integrity (the
 * receipt) establishes that the artifacts are the bound bytes; this class establishes that the envelope
 * DESCRIBES exactly those artifacts — every claimed association is verified against the artifact,
 * registry, or copied request it names, every object is closed (unknown nested members refuse), and every
 * artifact is accounted for exactly once (no orphans, no missing associations). A receipt-consistent but
 * internally contradictory packet therefore refuses verification before any policy runs. Problems are
 * collected with their location and refused once — never first-error-only.
 */
final class PacketRelations {

    /** The verified relational view {@code VerifiedPacket} wraps. */
    record Relations(ScenarioSet request, Map<String, Map<String, Json.Obj>> observationsByCase) {
    }

    private final Path dir;
    private final Json.Obj envelope;
    private final Receipt receipt;
    private final List<String> problems = new ArrayList<>();
    private final Set<String> expectedPaths = new LinkedHashSet<>();

    private PacketRelations(Path dir, Json.Obj envelope, Receipt receipt) {
        this.dir = dir;
        this.envelope = envelope;
        this.receipt = receipt;
    }

    static Relations check(Path dir, Json.Obj envelope, Receipt receipt) {
        PacketRelations relations = new PacketRelations(dir, envelope, receipt);
        Relations result = relations.checkAll();
        if (!relations.problems.isEmpty()) {
            throw new CaptureRefusedException("packet relations: the envelope does not describe the packet"
                    + " it travels with —\n  " + String.join("\n  ", relations.problems));
        }
        return result;
    }

    private Relations checkAll() {
        closed(envelope, "$", "schema", "operationSchema", "capabilities", "kernel", "capture", "scenario",
                "world", "runners", "inputs", "legality", "cases", "command");
        expectedPaths.add("PACKET.json");

        ScenarioSet request = checkScenarioAndRequest();
        checkCapabilities(request);
        String kernelVersion = checkKernel();
        checkCapture();
        checkWorld(request);
        checkRunners(request);
        Map<String, byte[]> modelBytes = checkInputs(request);
        Map<String, String> legalityVerdicts = checkLegality(request, modelBytes, kernelVersion);
        Map<String, Map<String, Json.Obj>> observations = checkCases(request, modelBytes, legalityVerdicts);
        checkNoOrphans();
        return new Relations(request, observations);
    }

    // ---- scenario ↔ copied request ---------------------------------------------------------------------

    private ScenarioSet checkScenarioAndRequest() {
        Json.Obj scenario = obj("scenario", "$");
        if (scenario == null) {
            return null;
        }
        closed(scenario, "$.scenario", "id", "version", "requestArtifact", "requestSha256");
        String artifact = str(scenario, "$.scenario", "requestArtifact");
        if (artifact != null && !"request/scenarios.json".equals(artifact)) {
            problems.add("$.scenario.requestArtifact: the copied request lives at request/scenarios.json");
        }
        byte[] requestBytes = read("request/scenarios.json");
        expectedPaths.add("request/scenarios.json");
        if (requestBytes == null) {
            return null;
        }
        String claimed = str(scenario, "$.scenario", "requestSha256");
        String actual = Digests.sha256(requestBytes);
        if (claimed != null && !claimed.equals(actual)) {
            problems.add("$.scenario.requestSha256: the claimed digest " + claimed
                    + " does not match the copied request bytes (" + actual + ")");
        }
        ScenarioSet request;
        try {
            request = ScenarioDecoder.decode(Json.parse(requestBytes));
        } catch (ScenarioRejectedException | IllegalArgumentException rejected) {
            problems.add("request/scenarios.json: the copied request no longer decodes under "
                    + SchemaIds.SCENARIO_SET + ": " + rejected.getMessage());
            return null;
        }
        if (!request.scenarioSetId().equals(str(scenario, "$.scenario", "id"))) {
            problems.add("$.scenario.id: the envelope's scenario identity disagrees with the copied request");
        }
        if (!request.scenarioSetVersion().equals(str(scenario, "$.scenario", "version"))) {
            problems.add("$.scenario.version: the envelope's scenario version disagrees with the copied request");
        }
        String operationSchema = str(envelope, "$", "operationSchema");
        if (operationSchema != null && !operationSchema.equals(request.operationSchema())) {
            problems.add("$.operationSchema: disagrees with the copied request");
        }
        return request;
    }

    // ---- capabilities ↔ registry -----------------------------------------------------------------------

    private void checkCapabilities(ScenarioSet request) {
        Json.Obj capabilities = obj("capabilities", "$");
        if (capabilities == null) {
            return;
        }
        closed(capabilities, "$.capabilities", "schema", "version", "artifact", "sha256");
        expectExact(capabilities, "$.capabilities", "schema", SchemaIds.CAPABILITIES);
        expectExact(capabilities, "$.capabilities", "version", SchemaIds.CAPABILITIES_VERSION);
        expectExact(capabilities, "$.capabilities", "artifact", "capabilities/capabilities.json");
        byte[] bytes = read("capabilities/capabilities.json");
        expectedPaths.add("capabilities/capabilities.json");
        if (bytes == null) {
            return;
        }
        String claimed = str(capabilities, "$.capabilities", "sha256");
        if (claimed != null && !claimed.equals(Digests.sha256(bytes))) {
            problems.add("$.capabilities.sha256: the claimed digest does not match the copied artifact");
        }
        byte[] registry = Json.canonicalBytes(Capabilities.emit());
        if (!java.util.Arrays.equals(bytes, registry)) {
            problems.add("capabilities/capabilities.json: the packet's capabilities copy disagrees with"
                    + " this verifier's compile-time registry — a capability/fidelity claim under "
                    + SchemaIds.CAPABILITIES + " must be the registry's exact canonical bytes");
        }
        if (request != null && (!request.requiredCapabilitiesSchema().equals(SchemaIds.CAPABILITIES)
                || !request.requiredCapabilitiesVersion().equals(SchemaIds.CAPABILITIES_VERSION))) {
            problems.add("$.capabilities: the request pins a different capability identity");
        }
    }

    // ---- kernel closure ---------------------------------------------------------------------------------

    private String checkKernel() {
        Json.Obj kernel = obj("kernel", "$");
        if (kernel == null) {
            return null;
        }
        closed(kernel, "$.kernel", "artifactCoordinate", "version", "artifactSha256", "closure");
        String anchorCoordinate = str(kernel, "$.kernel", "artifactCoordinate");
        String anchorVersion = str(kernel, "$.kernel", "version");
        String anchorSha = str(kernel, "$.kernel", "artifactSha256");
        if (anchorSha != null && !Digests.isSha256(anchorSha)) {
            problems.add("$.kernel.artifactSha256: not a lowercase-hex SHA-256");
        }
        if (!(kernel.members().get("closure") instanceof Json.Arr closure) || closure.items().isEmpty()) {
            problems.add("$.kernel.closure: the explicit resolved closure must be a non-empty artifact list");
            return anchorVersion;
        }
        boolean anchorListed = false;
        for (int i = 0; i < closure.items().size(); i++) {
            String at = "$.kernel.closure[" + i + "]";
            if (!(closure.items().get(i) instanceof Json.Obj artifact)) {
                problems.add(at + ": expected an object");
                continue;
            }
            closed(artifact, at, "coordinate", "version", "sha256");
            String sha = str(artifact, at, "sha256");
            if (sha != null && !Digests.isSha256(sha)) {
                problems.add(at + ".sha256: not a lowercase-hex SHA-256");
            }
            anchorListed |= anchorCoordinate != null
                    && anchorCoordinate.equals(str(artifact, at, "coordinate"))
                    && anchorVersion != null && anchorVersion.equals(str(artifact, at, "version"))
                    && anchorSha != null && anchorSha.equals(sha);
        }
        if (!anchorListed) {
            problems.add("$.kernel: the behavior anchor is not part of the recorded closure");
        }
        return anchorVersion;
    }

    private void checkCapture() {
        Json.Obj capture = obj("capture", "$");
        if (capture == null) {
            return;
        }
        closed(capture, "$.capture", "tool", "toolVersion", "sourceRevision", "workingTree",
                "developmentMode", "treeStateDigest", "runtime", "closureNote");
        String workingTree = str(capture, "$.capture", "workingTree");
        if (workingTree != null && !Set.of("clean", "dirty").contains(workingTree)) {
            problems.add("$.capture.workingTree: expected clean or dirty");
        }
        if (!(capture.members().get("developmentMode") instanceof Json.Bool)) {
            problems.add("$.capture.developmentMode: expected a boolean");
        }
        if (capture.members().get("runtime") instanceof Json.Obj runtime) {
            closed(runtime, "$.capture.runtime", "javaSpecificationVersion", "javaVendor",
                    "javaRuntimeVersion", "vmName");
        } else {
            problems.add("$.capture.runtime: expected the runtime identity object");
        }
    }

    private void checkWorld(ScenarioSet request) {
        Json.Obj world = obj("world", "$");
        if (world == null || request == null) {
            return;
        }
        closed(world, "$.world", "profile", "locale", "timezoneHandling");
        expectExact(world, "$.world", "profile", request.worldProfile());
        expectExact(world, "$.world", "locale", request.worldLocale());
        expectExact(world, "$.world", "timezoneHandling", "runner-local-wall-clock");
    }

    private void checkRunners(ScenarioSet request) {
        if (!(envelope.members().get("runners") instanceof Json.Arr runners) || request == null) {
            return;
        }
        List<RunnerId> declared = request.requiredRunners();
        if (runners.items().size() != declared.size()) {
            problems.add("$.runners: " + runners.items().size() + " runner descriptor(s) for "
                    + declared.size() + " requested runner(s)");
            return;
        }
        for (int i = 0; i < declared.size(); i++) {
            String at = "$.runners[" + i + "]";
            RunnerId expected = declared.get(i);
            if (!(runners.items().get(i) instanceof Json.Obj runner)) {
                problems.add(at + ": expected an object");
                continue;
            }
            closed(runner, at, "id", "role", "route");
            expectExact(runner, at, "id", expected.wire());
            expectExact(runner, at, "role", expected.role());
            expectExact(runner, at, "route", expected.route());
        }
    }

    // ---- inputs ↔ request + copied bytes ---------------------------------------------------------------

    private Map<String, byte[]> checkInputs(ScenarioSet request) {
        Map<String, byte[]> modelBytes = new LinkedHashMap<>();
        if (!(envelope.members().get("inputs") instanceof Json.Arr inputs) || request == null) {
            return modelBytes;
        }
        List<ScenarioSet.ModelRef> declared = request.models();
        if (inputs.items().size() != declared.size()) {
            problems.add("$.inputs: " + inputs.items().size() + " input(s) for " + declared.size()
                    + " declared model(s)");
            return modelBytes;
        }
        for (int i = 0; i < declared.size(); i++) {
            String at = "$.inputs[" + i + "]";
            ScenarioSet.ModelRef model = declared.get(i);
            if (!(inputs.items().get(i) instanceof Json.Obj input)) {
                problems.add(at + ": expected an object");
                continue;
            }
            closed(input, at, "ref", "artifact", "suppliedSha256", "adaptedSha256");
            expectExact(input, at, "ref", model.ref());
            expectExact(input, at, "artifact", "inputs/" + model.ref());
            byte[] bytes = read("inputs/" + model.ref());
            expectedPaths.add("inputs/" + model.ref());
            if (bytes == null) {
                continue;
            }
            modelBytes.put(model.ref(), bytes);
            String actual = Digests.sha256(bytes);
            if (!actual.equals(model.sha256())) {
                problems.add(at + ": the copied model bytes do not match the request-declared digest");
            }
            String supplied = str(input, at, "suppliedSha256");
            if (supplied != null && !supplied.equals(actual)) {
                problems.add(at + ".suppliedSha256: does not match the copied model bytes");
            }
            String adapted = str(input, at, "adaptedSha256");
            if (adapted != null && !adapted.equals(supplied)) {
                problems.add(at + ".adaptedSha256: v1 adapts nothing — supplied and adapted must be equal");
            }
        }
        return modelBytes;
    }

    // ---- legality ↔ artifacts --------------------------------------------------------------------------

    private Map<String, String> checkLegality(ScenarioSet request, Map<String, byte[]> modelBytes,
            String kernelVersion) {
        Map<String, String> verdicts = new LinkedHashMap<>();
        if (!(envelope.members().get("legality") instanceof Json.Arr legality) || request == null) {
            return verdicts;
        }
        List<ScenarioSet.ModelRef> declared = request.models();
        if (legality.items().size() != declared.size()) {
            problems.add("$.legality: " + legality.items().size() + " record(s) for " + declared.size()
                    + " declared model(s)");
            return verdicts;
        }
        for (int i = 0; i < declared.size(); i++) {
            String at = "$.legality[" + i + "]";
            ScenarioSet.ModelRef model = declared.get(i);
            if (!(legality.items().get(i) instanceof Json.Obj record)) {
                problems.add(at + ": expected an object");
                continue;
            }
            closed(record, at, "ref", "artifact", "sha256", "verdict");
            expectExact(record, at, "ref", model.ref());
            expectExact(record, at, "artifact", "legality/" + model.ref());
            byte[] bytes = read("legality/" + model.ref());
            expectedPaths.add("legality/" + model.ref());
            if (bytes == null) {
                continue;
            }
            String claimed = str(record, at, "sha256");
            if (claimed != null && !claimed.equals(Digests.sha256(bytes))) {
                problems.add(at + ".sha256: the claimed digest does not match the legality artifact bytes");
            }
            String verdict = str(record, at, "verdict");
            checkLegalityArtifact(at, bytes, model, verdict, kernelVersion, modelBytes.get(model.ref()));
            if (verdict != null) {
                verdicts.put(model.ref(), verdict);
            }
        }
        return verdicts;
    }

    private void checkLegalityArtifact(String at, byte[] bytes, ScenarioSet.ModelRef model, String verdict,
            String kernelVersion, byte[] copiedModel) {
        if (!(Json.parse(bytes) instanceof Json.Obj artifact)) {
            problems.add(at + ": the legality artifact is not an object");
            return;
        }
        closed(artifact, at + ".artifact", "schema", "validator", "modelRef", "suppliedSha256",
                "adaptedSha256", "verdict", "notifications");
        expectExact(artifact, at + ".artifact", "schema", SchemaIds.LEGALITY);
        expectExact(artifact, at + ".artifact", "modelRef", model.ref());
        if (copiedModel != null) {
            expectExact(artifact, at + ".artifact", "suppliedSha256", Digests.sha256(copiedModel));
        }
        String supplied = str(artifact, at + ".artifact", "suppliedSha256");
        String adapted = str(artifact, at + ".artifact", "adaptedSha256");
        if (adapted != null && !adapted.equals(supplied)) {
            problems.add(at + ".artifact.adaptedSha256: v1 adapts nothing — the gate must have inspected"
                    + " the same bytes runtime consumed");
        }
        String innerVerdict = str(artifact, at + ".artifact", "verdict");
        if (innerVerdict != null && verdict != null && !innerVerdict.equals(verdict)) {
            problems.add(at + ".verdict: the envelope claims \"" + verdict + "\" but the legality artifact"
                    + " records \"" + innerVerdict + "\" — an internal contradiction");
        }
        if (artifact.members().get("validator") instanceof Json.Obj validator) {
            closed(validator, at + ".artifact.validator", "runner", "kernelVersion");
            if (kernelVersion != null) {
                expectExact(validator, at + ".artifact.validator", "kernelVersion", kernelVersion);
            }
        } else {
            problems.add(at + ".artifact.validator: expected an object");
        }
        if (artifact.members().get("notifications") instanceof Json.Arr notifications) {
            boolean anyError = false;
            for (int i = 0; i < notifications.items().size(); i++) {
                if (notifications.items().get(i) instanceof Json.Obj notification) {
                    closed(notification, at + ".artifact.notifications[" + i + "]",
                            "severity", "code", "textSha256");
                    anyError |= notification.members().get("severity") instanceof Json.Str s
                            && "ERROR".equals(s.value());
                } else {
                    problems.add(at + ".artifact.notifications[" + i + "]: expected an object");
                }
            }
            if (innerVerdict != null && !innerVerdict.equals(anyError ? "illegal" : "legal")) {
                problems.add(at + ".artifact.verdict: \"" + innerVerdict + "\" contradicts the recorded"
                        + " notifications");
            }
        } else {
            problems.add(at + ".artifact.notifications: expected an array");
        }
    }

    // ---- cases ↔ request ↔ observations ----------------------------------------------------------------

    private Map<String, Map<String, Json.Obj>> checkCases(ScenarioSet request,
            Map<String, byte[]> modelBytes, Map<String, String> legalityVerdicts) {
        Map<String, Map<String, Json.Obj>> observations = new LinkedHashMap<>();
        if (!(envelope.members().get("cases") instanceof Json.Arr cases) || request == null) {
            return observations;
        }
        List<ScenarioSet.ScenarioCase> declared = request.cases();
        if (cases.items().size() != declared.size()) {
            problems.add("$.cases: " + cases.items().size() + " case entr(ies) for " + declared.size()
                    + " request case(s)");
            return observations;
        }
        for (int i = 0; i < declared.size(); i++) {
            String at = "$.cases[" + i + "]";
            ScenarioSet.ScenarioCase requestCase = declared.get(i);
            if (!(cases.items().get(i) instanceof Json.Obj caseObj)) {
                problems.add(at + ": expected an object");
                continue;
            }
            closed(caseObj, at, "caseId", "modelRef", "legality", "executed", "observations");
            expectExact(caseObj, at, "caseId", requestCase.caseId());
            expectExact(caseObj, at, "modelRef", requestCase.modelRef());
            String verdict = legalityVerdicts.get(requestCase.modelRef());
            if (verdict != null) {
                expectExact(caseObj, at, "legality", verdict);
            }
            boolean executed = caseObj.members().get("executed") instanceof Json.Bool b && b.value();
            if (verdict != null && executed != "legal".equals(verdict)) {
                problems.add(at + ".executed: contradicts the model's legality verdict");
            }
            observations.put(requestCase.caseId(),
                    checkObservationRefs(at, caseObj, requestCase, executed, request, modelBytes));
        }
        return observations;
    }

    private Map<String, Json.Obj> checkObservationRefs(String at, Json.Obj caseObj,
            ScenarioSet.ScenarioCase requestCase, boolean executed, ScenarioSet request,
            Map<String, byte[]> modelBytes) {
        Map<String, Json.Obj> byRunner = new LinkedHashMap<>();
        if (!(caseObj.members().get("observations") instanceof Json.Arr refs)) {
            problems.add(at + ".observations: expected an array");
            return byRunner;
        }
        if (!executed) {
            if (!refs.items().isEmpty()) {
                problems.add(at + ".observations: a non-executed case truthfully carries no observations");
            }
            return byRunner;
        }
        List<RunnerId> declared = request.requiredRunners();
        if (refs.items().size() != declared.size()) {
            problems.add(at + ".observations: " + refs.items().size() + " observation ref(s) for "
                    + declared.size() + " required runner(s) — every required runner appears exactly once");
            return byRunner;
        }
        for (int i = 0; i < declared.size(); i++) {
            String refAt = at + ".observations[" + i + "]";
            RunnerId runner = declared.get(i);
            if (!(refs.items().get(i) instanceof Json.Obj ref)) {
                problems.add(refAt + ": expected an object");
                continue;
            }
            closed(ref, refAt, "runner", "artifact", "sha256", "status");
            expectExact(ref, refAt, "runner", runner.wire());
            String artifactPath = "observations/" + requestCase.caseId() + "/" + runner.wire() + ".json";
            expectExact(ref, refAt, "artifact", artifactPath);
            byte[] bytes = read(artifactPath);
            expectedPaths.add(artifactPath);
            if (bytes == null) {
                continue;
            }
            String claimed = str(ref, refAt, "sha256");
            if (claimed != null && !claimed.equals(Digests.sha256(bytes))) {
                problems.add(refAt + ".sha256: the claimed digest does not match the observation artifact");
            }
            if (!(Json.parse(bytes) instanceof Json.Obj observation)) {
                problems.add(refAt + ": the observation artifact is not an object");
                continue;
            }
            new ObservationShape(problems, refAt + ".artifact").check(observation, runner);
            checkObservationRelations(refAt, observation, ref, requestCase, runner, modelBytes);
            byRunner.put(runner.wire(), observation);
        }
        return byRunner;
    }

    private void checkObservationRelations(String refAt, Json.Obj observation, Json.Obj ref,
            ScenarioSet.ScenarioCase requestCase, RunnerId runner, Map<String, byte[]> modelBytes) {
        expectExact(observation, refAt + ".artifact", "caseId", requestCase.caseId());
        expectExact(observation, refAt + ".artifact", "runner", runner.wire());
        String refStatus = str(ref, refAt, "status");
        String artifactStatus = str(observation, refAt + ".artifact", "status");
        if (refStatus != null && artifactStatus != null && !refStatus.equals(artifactStatus)) {
            problems.add(refAt + ".status: the envelope claims \"" + refStatus + "\" but the artifact"
                    + " records \"" + artifactStatus + "\" — an internal contradiction");
        }
        if (observation.members().get("consumedModel") instanceof Json.Obj consumedModel) {
            expectExact(consumedModel, refAt + ".artifact.consumedModel", "ref", requestCase.modelRef());
            byte[] copied = modelBytes.get(requestCase.modelRef());
            if (copied != null) {
                expectExact(consumedModel, refAt + ".artifact.consumedModel", "suppliedSha256",
                        Digests.sha256(copied));
                expectExact(consumedModel, refAt + ".artifact.consumedModel", "adaptedSha256",
                        Digests.sha256(copied));
            }
        }
        if ("success".equals(artifactStatus)
                && observation.members().get("consumedDocument") instanceof Json.Obj consumedDocument) {
            ConsumedDocument supplied = CaseDocumentIdentity.ofCase(requestCase);
            String consumedSha = str(consumedDocument, refAt + ".artifact.consumedDocument", "canonicalSha256");
            if (consumedSha != null && !consumedSha.equals(supplied.canonicalSha256())) {
                problems.add(refAt + ".artifact.consumedDocument: the runner-consumed placement identity"
                        + " does not match the request case (v1 adapts nothing — supplied must equal"
                        + " consumed); a placement was changed or dropped during adaptation");
            }
            if (consumedDocument.members().get("placements") instanceof Json.Int count
                    && count.value() != supplied.placements()) {
                problems.add(refAt + ".artifact.consumedDocument.placements: " + count.value()
                        + " applied for " + supplied.placements() + " declared placements");
            }
        }
    }

    // ---- exactly-once accounting -----------------------------------------------------------------------

    private void checkNoOrphans() {
        Set<String> actual = new LinkedHashSet<>();
        receipt.artifacts().forEach(a -> actual.add(a.path()));
        for (String path : actual) {
            if (!expectedPaths.contains(path)) {
                problems.add("receipt: artifact \"" + path + "\" is inventoried but referenced by nothing"
                        + " in the envelope — an orphan cannot ride along");
            }
        }
        for (String path : expectedPaths) {
            if (!actual.contains(path)) {
                problems.add("receipt: expected artifact \"" + path + "\" is not inventoried");
            }
        }
    }

    // ---- plumbing ---------------------------------------------------------------------------------------

    private Json.Obj obj(String member, String at) {
        if (envelope.members().get(member) instanceof Json.Obj obj) {
            return obj;
        }
        problems.add(at + "." + member + ": expected an object");
        return null;
    }

    private void closed(Json.Obj obj, String at, String... known) {
        Set<String> knownSet = Set.of(known);
        for (String member : obj.members().keySet()) {
            if (!knownSet.contains(member)) {
                problems.add(at + ": unknown member \"" + member + "\" — " + SchemaIds.EVIDENCE_PACKET
                        + " and its artifacts are closed");
            }
        }
        for (String member : known) {
            if (!obj.members().containsKey(member)) {
                problems.add(at + ": missing required member \"" + member + "\"");
            }
        }
    }

    private String str(Json.Obj obj, String at, String member) {
        if (obj.members().get(member) instanceof Json.Str s) {
            return s.value();
        }
        if (obj.members().containsKey(member)) {
            problems.add(at + "." + member + ": expected a string");
        }
        return null;
    }

    private void expectExact(Json.Obj obj, String at, String member, String expected) {
        String actual = str(obj, at, member);
        if (actual != null && !actual.equals(expected)) {
            problems.add(at + "." + member + ": \"" + actual + "\" does not match the verified value \""
                    + expected + "\"");
        }
    }

    /** Read a packet artifact, or record a problem and return null. */
    private byte[] read(String relativePath) {
        Path file = dir.resolve(relativePath);
        if (!Files.isRegularFile(file)) {
            problems.add(relativePath + ": the referenced artifact does not exist in the packet");
            return null;
        }
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read " + file, e);
        }
    }
}
