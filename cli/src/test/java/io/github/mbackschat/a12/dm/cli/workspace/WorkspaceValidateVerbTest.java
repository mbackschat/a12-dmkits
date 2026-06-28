package io.github.mbackschat.a12.dm.cli.workspace;

import io.github.mbackschat.a12.dm.cli.CliTestSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.rulekit.write.ModelWriter;

/**
 * {@code workspace check} — the project gate: kernel-validate every model in a directory, per-model
 * diagnostics, and exit 1 if any is invalid.
 */
class WorkspaceValidateVerbTest {

    private static final ObjectMapper M = new ObjectMapper();

    private static String model(String id, String group, String field, FieldKind kind) {
        return DocumentModelDraft.named(id).locale("en_US")
                .rootGroup(group, g -> g.field(field, kind)).build().toJson();
    }

    @Test
    void allValidModelsPassWithExitZero(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("alpha.dm.json"), model("alpha", "Alpha", "Name", FieldKind.STRING));
        Files.writeString(dir.resolve("beta.dm.json"), model("beta", "Beta", "Amount", FieldKind.NUMBER));

        var res = CliTestSupport.run("workspace", "check", dir.toString());
        assertEquals(0, res.code(), () -> "all-valid workspace passes the gate: " + res.err() + res.out());
        JsonNode j = M.readTree(res.out());
        assertEquals("read", j.get("outcome").asText());
        assertTrue(j.get("valid").asBoolean(), "aggregate valid when every model is valid");
        assertEquals(0, j.path("data").path("invalid").asInt());
        assertEquals(2, j.path("data").path("count").asInt());
        for (JsonNode m : j.path("data").path("models")) {
            assertTrue(m.get("valid").asBoolean(), () -> "each model valid: " + m);
        }
    }

    @Test
    void anyInvalidModelFailsTheGateWithExitOneAndPerModelDiagnostics(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("good.dm.json"), model("good", "Good", "Name", FieldKind.STRING));
        // a valid model whose rule condition is then corrupted (ModelWriter does not re-validate) — kernel-invalid
        String brokenJson = ModelWriter.replaceCondition(
                CliTestSupport.modelWithRuleJson(), CliTestSupport.RULE_FULL_NAME, "@@@ not a condition @@@");
        Files.writeString(dir.resolve("broken.dm.json"), brokenJson);

        var res = CliTestSupport.run("workspace", "check", dir.toString());
        assertEquals(1, res.code(), "any invalid model fails the gate (exit 1)");
        JsonNode j = M.readTree(res.out());
        assertEquals("read", j.get("outcome").asText(), "still a read — the op ran");
        assertFalse(j.get("valid").asBoolean(), "aggregate invalid when any model is invalid");
        assertEquals(1, j.path("data").path("invalid").asInt());

        JsonNode broken = null;
        JsonNode good = null;
        for (JsonNode m : j.path("data").path("models")) {
            if (m.get("path").asText().contains("broken")) {
                broken = m;
            } else if (m.get("path").asText().contains("good")) {
                good = m;
            }
        }
        final JsonNode brokenEntry = broken;
        final JsonNode goodEntry = good;
        assertTrue(brokenEntry != null && goodEntry != null, () -> "both models present: " + j);
        assertFalse(brokenEntry.get("valid").asBoolean(), "the broken model is flagged invalid");
        assertTrue(brokenEntry.path("diagnostics").size() > 0, () -> "with per-model diagnostics: " + brokenEntry);
        assertTrue(goodEntry.get("valid").asBoolean(), "the good model stays valid (per-model attribution)");
    }

    @Test
    void aNonDirectoryIsACorrectiveFail(@TempDir Path dir) throws Exception {
        Path notADir = dir.resolve("nope");
        var res = CliTestSupport.run("workspace", "check", notADir.toString());
        assertEquals(2, res.code(), "a missing directory is a usage error");
        assertTrue(res.err().toLowerCase().contains("not a directory"), () -> "corrective message: " + res.err());
    }
}
