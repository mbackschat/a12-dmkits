package io.github.mbackschat.a12.rulekit.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.mbackschat.a12.rulekit.write.ModelWriter;

/** The {@code validate} verb — runs the model through the real kernel and reports consistency. */
class ValidateVerbTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void validateAcceptsAConsistentModel(@TempDir Path dir) throws Exception {
        var res = CliTestSupport.run("validate", CliTestSupport.modelWithRule(dir).toString());
        assertEquals(0, res.code(), res::err);
        JsonNode j = MAPPER.readTree(res.out());
        assertTrue(j.get("valid").asBoolean(), "a consistent model validates");
        assertTrue(j.get("diagnostics").isEmpty(), "with no diagnostics");
    }

    @Test
    void validateRejectsABrokenModelWithDiagnostics(@TempDir Path dir) throws Exception {
        // Corrupt only the condition slot (ModelWriter does not re-validate), then validate the file.
        String broken = ModelWriter.replaceCondition(
                CliTestSupport.modelWithRuleJson(), CliTestSupport.RULE_FULL_NAME, "@@@ not a condition @@@");
        Path file = dir.resolve("broken.dm.json");
        Files.writeString(file, broken);

        var res = CliTestSupport.run("validate", file.toString());
        assertEquals(1, res.code(), "an invalid model exits non-zero");
        JsonNode j = MAPPER.readTree(res.out());
        assertFalse(j.get("valid").asBoolean(), "valid is false");
        assertTrue(j.get("diagnostics").size() > 0, () -> "with at least one diagnostic: " + res.out());
    }

    @Test
    void validateRejectsExtraArgsAndPointsAtCheck(@TempDir Path dir) throws Exception {
        // N6 baseline finding: validate used to silently ignore extra args. Now it errors + redirects.
        var res = CliTestSupport.run("validate", CliTestSupport.modelWithRule(dir).toString(), "MonthlyFee > 0");
        assertNotEquals(0, res.code(), "extra args are no longer silently ignored");
        assertTrue(res.err().toLowerCase().contains("check"), () -> "points at the check verb: " + res.err());
    }
}
