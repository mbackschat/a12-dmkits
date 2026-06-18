package io.github.mbackschat.a12.dm.cli;

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
 * The {@code explain} verb — read an existing rule ({@code --rule}) or computation ({@code --computation}),
 * then project it to a structured explanation.
 */
class ExplainVerbTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static Path modelWithRule(Path dir) throws IOException {
        Path f = dir.resolve("m.dm.json");
        Files.writeString(f, CliTestSupport.modelWithRuleJson());
        return f;
    }

    private static Path modelWithRuleAndComputation(Path dir) throws IOException {
        Path f = dir.resolve("m.dm.json");
        Files.writeString(f, CliTestSupport.modelWithRuleAndComputationJson());
        return f;
    }

    @Test
    void explainsAnExistingRule(@TempDir Path dir) throws Exception {
        var res = CliTestSupport.run("-m", modelWithRule(dir).toString(),
                "rule", "explain", CliTestSupport.RULE_FULL_NAME);
        assertEquals(0, res.code(), res::err);
        JsonNode data = MAPPER.readTree(res.out()).path("data");
        assertTrue(data.has("operators") && data.has("references"),
                () -> "explanation carries operators + references: " + res.out());
        assertTrue(res.out().contains("MonthlyFee"), "names the referenced field");
    }

    @Test
    void explainsAComputation(@TempDir Path dir) throws Exception {
        var res = CliTestSupport.run("-m", modelWithRuleAndComputation(dir).toString(),
                "computation", "explain", CliTestSupport.COMPUTATION_FULL_NAME);
        assertEquals(0, res.code(), res::err);
        JsonNode data = MAPPER.readTree(res.out()).path("data");
        assertEquals("/Subscription/Billing/EffectiveFee", data.get("computedField").asText());
        assertEquals("NUMBER", data.get("computedFieldKind").asText());
        assertTrue(data.has("alternatives") && data.get("alternatives").size() == 1,
                () -> "the computation's single alternative: " + res.out());
        assertTrue(res.out().contains("BaseFee"), "the operation reads BaseFee");
    }

    @Test
    void ruleExplainOnAComputationRedirects(@TempDir Path dir) throws Exception {
        var res = CliTestSupport.run("-m", modelWithRuleAndComputation(dir).toString(),
                "rule", "explain", CliTestSupport.COMPUTATION_FULL_NAME);
        assertNotEquals(0, res.code(), "a computation is not a validation rule");
        assertTrue(res.err().contains("computation explain"),
                () -> "redirects to the `computation explain` verb: " + res.err());
    }

    @Test
    void missingPathFails(@TempDir Path dir) throws Exception {
        var res = CliTestSupport.run("-m", modelWithRule(dir).toString(), "rule", "explain");
        assertNotEquals(0, res.code(), "rule explain needs the rule path");
    }
}
