package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Probe 01-de Finding 7 — a verb that LOADS a model whose stored content is already kernel-invalid must not
 * leak a raw {@code ModelConsistencyException} stack trace (the P1 "no raw exception escapes a verb" invariant,
 * on the model-LOAD path that P1's candidate-validation fix didn't cover). The strict {@code ofJson} load
 * throws on an invalid stored model; an execution-exception handler maps it to a structured {@code rejected}
 * envelope. Covers ≥2 load verbs (the guard is for the class, not one verb).
 */
class InvalidModelLoadDiagnosticTest {

    private static final ObjectMapper M = new ObjectMapper();

    private record StrictLoadCase(String name, List<String> args) {
        private StrictLoadCase(String name, String... args) {
            this(name, List.of(args));
        }
    }

    /** A model that DESERIALIZES but fails checkConsistency at load: a rule whose operand names a foreign root. */
    private static Path invalidModel(Path dir) throws Exception {
        Path m = dir.resolve("bad.json");
        Files.writeString(m, """
                { "header": { "id": "bad", "modelType": "document", "modelVersion": "28.4.0",
                    "locales": [ { "code": "en_US" } ], "labels": [], "annotations": [], "modelReferences": [] },
                  "content": {
                    "modelInfo": { "name": "bad", "variant": "cockpit", "revision": "r", "immutable": false },
                    "modelConfig": { "decimalSeparator": ".", "timeZone": "Europe/Berlin",
                      "conditionLanguage": { "code": "en_US" }, "fieldRefByShortNameAllowed": true },
                    "modelRoot": { "rootGroups": [ { "type": "Group", "id": "/R", "name": "R", "Group": {
                      "repeatability": 1, "elements": [
                        { "type": "Field", "id": "/R/A", "name": "A", "Field": { "fieldType": { "type": "StringType" } } },
                        { "type": "Rule", "id": "/R/BAD", "name": "BAD", "Rule": {
                          "errorEntityRelPath": "../A", "errorCode": "BAD",
                          "errorCondition": "FieldFilled(/Other/A)", "severity": "ERROR",
                          "errorMessage": [ { "locale": "en_US", "text": "x" } ] } } ] } } ] } } }""");
        return m;
    }

    private static void assertNoStackTrace(CliTestSupport.Result res) {
        String all = res.out() + "\n" + res.err();
        assertFalse(all.contains("ModelConsistencyException") || all.matches("(?s).*\\n\\tat [\\w.$]+\\(.*"),
                () -> "a raw stack trace escaped the verb:\n" + all);
    }

    @Test
    void everyStrictReaderPreservesAnInvalidStoredModelAsTheCentralStructuredRejection(@TempDir Path dir)
            throws Exception {
        Path model = invalidModel(dir);
        List<StrictLoadCase> cases = List.of(
                new StrictLoadCase("model describe", "model", "describe"),
                new StrictLoadCase("model read", "model", "read"),
                new StrictLoadCase("model report", "model", "report"),
                new StrictLoadCase("model usage", "model", "usage"),
                new StrictLoadCase("rule read", "rule", "read", "/R/BAD"),
                new StrictLoadCase("rule deps", "rule", "deps", "/R/BAD"),
                new StrictLoadCase("rule explain", "rule", "explain", "/R/BAD"),
                new StrictLoadCase("rule format", "rule", "format", "/R/BAD"),
                new StrictLoadCase("computation read", "computation", "read"),
                new StrictLoadCase("computation explain", "computation", "explain", "/R/AnyComputation"),
                new StrictLoadCase("computation format", "computation", "format", "/R/AnyComputation"),
                new StrictLoadCase("where-used", "where-used", "/R/A"),
                new StrictLoadCase("export", "export", "card"));

        for (StrictLoadCase testCase : cases) {
            List<String> args = new ArrayList<>(List.of("-m", model.toString()));
            args.addAll(testCase.args());
            CliTestSupport.Result res = CliTestSupport.run(args.toArray(String[]::new));

            assertNotEquals(0, res.code(), testCase.name() + ": an invalid-on-disk model is a refusal, not success");
            assertNoStackTrace(res);
            var env = M.readTree(res.out());
            assertEquals("rejected", env.path("outcome").asText(),
                    () -> testCase.name() + ": expected the central structured rejection: " + res.out());
            assertTrue(res.out().contains("MVK_INVALID_ROOT_GROUP"),
                    () -> testCase.name() + ": the kernel code must surface in the envelope: " + res.out());
        }
    }

    @Test
    void strictReaderPreservesAnUnresolvedIncludeAsTheCentralDomainRejection(@TempDir Path dir) throws Exception {
        String json = DocumentModelDraft.named("host").locale("en_US")
                .include("missing-library", "missing-library")
                .rootGroup("Host", host -> host.mount("Missing", "missing-library"))
                .build().toJson();
        Path model = dir.resolve("host.json");
        Files.writeString(model, json);

        CliTestSupport.Result res = CliTestSupport.run("-m", model.toString(), "model", "read");

        assertEquals(1, res.code(), "an unresolved model reference is an input rejection");
        assertNoStackTrace(res);
        var env = M.readTree(res.out());
        assertEquals("rejected", env.path("outcome").asText(), res::out);
        assertEquals("RK_UNRESOLVED_REFERENCE",
                env.path("diagnostics").path(0).path("code").asText(), res::out);
    }
}
