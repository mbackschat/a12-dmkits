package io.github.mbackschat.a12.rulekit.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** The {@code explain} verb — ingest an existing rule, then project it to a structured RuleExplanation. */
class ExplainVerbTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static Path modelWithRule(Path dir) throws IOException {
        Path f = dir.resolve("m.dm.json");
        Files.writeString(f, CliTestSupport.modelWithRuleJson());
        return f;
    }

    @Test
    void explainsAnExistingRule(@TempDir Path dir) throws Exception {
        var res = CliTestSupport.run("explain", modelWithRule(dir).toString(),
                "--rule", CliTestSupport.RULE_FULL_NAME);
        assertEquals(0, res.code(), res::err);
        JsonNode j = MAPPER.readTree(res.out());
        assertTrue(j.has("operators") && j.has("references"),
                () -> "explanation carries operators + references: " + res.out());
        assertTrue(res.out().contains("MonthlyFee"), "names the referenced field");
    }
}
