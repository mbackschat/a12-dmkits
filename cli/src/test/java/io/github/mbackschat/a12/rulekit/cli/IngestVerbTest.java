package io.github.mbackschat.a12.rulekit.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The {@code ingest} verb — read an existing rule's condition back into rulekit's AST via the adapter
 * (F10). Tolerant: an unmodeled construct returns {@code converted:false} with the verbatim text, not
 * an error.
 */
class IngestVerbTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** A subscription model carrying the FeeCapRule (`[MonthlyFee] > 1000`), written to a temp file. */
    private static Path modelWithRule(Path dir) throws IOException {
        Path f = dir.resolve("m.dm.json");
        Files.writeString(f, CliTestSupport.modelWithRuleJson());
        return f;
    }

    @Test
    void ingestsAnExistingRuleToARenderableCondition(@TempDir Path dir) throws Exception {
        var res = CliTestSupport.run("ingest", modelWithRule(dir).toString(),
                "--rule", CliTestSupport.RULE_FULL_NAME);
        assertEquals(0, res.code(), res::err);
        JsonNode j = MAPPER.readTree(res.out());
        assertTrue(j.get("converted").asBoolean(), "a modeled condition converts");
        String condition = j.get("condition").asText();
        assertTrue(condition.contains("MonthlyFee") && condition.contains("1000"),
                () -> "round-trips to a renderable condition: " + condition);
    }

    @Test
    void missingRuleFlagFails(@TempDir Path dir) throws Exception {
        var res = CliTestSupport.run("ingest", modelWithRule(dir).toString());
        assertNotEquals(0, res.code(), "ingest needs --rule");
        assertTrue(res.err().toLowerCase().contains("rule"), () -> "names the gap: " + res.err());
    }
}
