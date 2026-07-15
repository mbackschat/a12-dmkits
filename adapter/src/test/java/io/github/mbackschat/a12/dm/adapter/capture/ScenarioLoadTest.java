package io.github.mbackschat.a12.dm.adapter.capture;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import io.github.mbackschat.a12.dm.adapter.capture.json.Digests;
import io.github.mbackschat.a12.dm.adapter.capture.schema.ScenarioRejectedException;
import io.github.mbackschat.a12.dm.adapter.capture.schema.ScenarioSets;

/**
 * The file-level half of the ONE shared acceptance pipeline ({@code ScenarioSets.load}): refs resolve only
 * inside the scenario root (symlinks refused), the declared SHA-256 binds the exact model bytes, and the
 * kernel-free model-structure gates run — placements/probes must name model elements, and the v1
 * input-support gate rejects repeatable computation targets/scopes (proposal §1/§4). No engine runs, no
 * evidence is written.
 */
class ScenarioLoadTest {

    @Test
    void loadsTheValidScenarioRootWithVerifiedDigests(@TempDir Path root) {
        Path scenario = CaptureFixtures.writeValidScenario(root);
        ScenarioSets.Loaded loaded = ScenarioSets.load(scenario);
        assertEquals("capture-selftest-quote-v1", loaded.set().scenarioSetId());
        ScenarioSets.ModelBytes model = loaded.models().get("models/quote-premium.json");
        assertArrayEquals(CaptureFixtures.resourceBytes(CaptureFixtures.QUOTE_PREMIUM), model.bytes(),
                "the exact request bytes are what capture will copy into the packet");
        assertEquals(Digests.sha256(model.bytes()), model.sha256());
    }

    @Test
    void rejectsADigestMismatchNamingBothDigests(@TempDir Path root) throws IOException {
        Path scenario = CaptureFixtures.writeValidScenario(root);
        // Corrupt the model AFTER the scenario declared its digest — the request binds exact bytes.
        Path model = root.resolve("models/quote-premium.json");
        Files.writeString(model, Files.readString(model).replace("\"maxLength\": 40", "\"maxLength\": 41"));
        List<String> problems = assertThrows(ScenarioRejectedException.class,
                () -> ScenarioSets.load(scenario)).problems();
        assertTrue(problems.stream().anyMatch(p -> p.contains("does not match the file's actual")),
                problems::toString);
    }

    @Test
    void rejectsARefThatDoesNotResolve(@TempDir Path root) {
        Path scenario = CaptureFixtures.writeScenario(root,
                text -> text.replace("models/quote-premium.json", "models/gone.json"));
        List<String> problems = assertThrows(ScenarioRejectedException.class,
                () -> ScenarioSets.load(scenario)).problems();
        assertTrue(problems.stream().anyMatch(p -> p.contains("does not resolve to a file")), problems::toString);
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void rejectsASymlinkedModel(@TempDir Path root) throws IOException {
        Path scenario = CaptureFixtures.writeValidScenario(root);
        Path model = root.resolve("models/quote-premium.json");
        Path real = root.resolve("elsewhere.json");
        Files.move(model, real);
        Files.createSymbolicLink(model, real);
        List<String> problems = assertThrows(ScenarioRejectedException.class,
                () -> ScenarioSets.load(scenario)).problems();
        assertTrue(problems.stream().anyMatch(p -> p.contains("symlink")), problems::toString);
    }

    @Test
    void rejectsARepeatableComputationTarget(@TempDir Path root) {
        String repSha = CaptureFixtures.placeModel(root, CaptureFixtures.QUOTE_LINES_REP, "quote-lines-rep.json");
        Path scenario = CaptureFixtures.writeScenario(root, text -> text
                .replace("models/quote-premium.json", "models/quote-lines-rep.json")
                .replaceFirst("\"sha256\": \"[0-9a-f]{64}\"", "\"sha256\": \"" + repSha + "\"")
                .replace("{ \"kind\": \"FIELD\", \"path\": \"/Quote/BaseRate\", \"reps\": [1, 1], \"value\": \"10.25\" },",
                        "{ \"kind\": \"GROUP\", \"path\": \"/Quote/Lines\", \"reps\": [1, 1] },"
                                + " { \"kind\": \"FIELD\", \"path\": \"/Quote/Lines/Hours\", \"reps\": [1, 1, 1], \"value\": \"3\" },")
                .replace("{ \"kind\": \"EMPTY\", \"path\": \"/Quote/Premium\", \"reps\": [1, 1] }",
                        "{ \"kind\": \"EMPTY\", \"path\": \"/Quote/Lines/LineCost\", \"reps\": [1, 1, 1] }")
                .replace("\"probes\": [ \"/Quote[1]/Premium\" ]",
                        "\"probes\": [ \"/Quote[1]/Lines[1]/LineCost\" ]"));
        List<String> problems = assertThrows(ScenarioRejectedException.class,
                () -> ScenarioSets.load(scenario)).problems();
        assertTrue(problems.stream().anyMatch(p ->
                        p.contains("repeatable") && p.contains("non-repeatable-compute-targets-v1")),
                problems::toString);
    }

    @Test
    void rejectsAPlacementPathTheModelDoesNotDeclare(@TempDir Path root) {
        Path scenario = CaptureFixtures.writeScenario(root,
                text -> text.replace("\"path\": \"/Quote/BaseRate\"", "\"path\": \"/Quote/NoSuchField\""));
        List<String> problems = assertThrows(ScenarioRejectedException.class,
                () -> ScenarioSets.load(scenario)).problems();
        assertTrue(problems.stream().anyMatch(p -> p.contains("/Quote/NoSuchField")
                && p.contains("not a field of the model")), problems::toString);
    }

    @Test
    void rejectsAProbeThatNamesNoModelField(@TempDir Path root) {
        Path scenario = CaptureFixtures.writeScenario(root,
                text -> text.replace("\"probes\": [ \"/Quote[1]/Premium\" ]",
                        "\"probes\": [ \"/Quote[1]/Phantom\" ]"));
        List<String> problems = assertThrows(ScenarioRejectedException.class,
                () -> ScenarioSets.load(scenario)).problems();
        assertTrue(problems.stream().anyMatch(p -> p.contains("/Quote[1]/Phantom")
                && p.contains("does not name a field")), problems::toString);
    }
}
