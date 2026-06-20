package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.ObjectMapper;

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
    void ruleModifyOnAnInvalidStoredModelGivesAStructuredRejection_notAStackTrace(@TempDir Path dir) throws Exception {
        Path m = invalidModel(dir);
        CliTestSupport.Result res = CliTestSupport.run("-m", m.toString(), "rule", "modify", "/R/BAD",
                "--condition", "FieldFilled(/R/A)");
        assertNotEquals(0, res.code(), "an invalid-on-disk model is a refusal, not success");
        assertNoStackTrace(res);
        // structured envelope on stdout carrying the kernel's code
        var env = M.readTree(res.out());
        assertEquals("rejected", env.path("outcome").asText(), () -> "a structured rejected envelope: " + res.out());
        assertTrue(res.out().contains("MVK_INVALID_ROOT_GROUP"),
                () -> "the kernel's code surfaces in the envelope: " + res.out());
    }

    @Test
    void modelDescribeOnAnInvalidStoredModelDoesNotLeakAStackTrace(@TempDir Path dir) throws Exception {
        // the class, not one verb: another load verb on the same invalid model must also stay trace-free
        CliTestSupport.Result res = CliTestSupport.run("-m", invalidModel(dir).toString(), "model", "describe");
        assertNotEquals(0, res.code());
        assertNoStackTrace(res);
    }
}
