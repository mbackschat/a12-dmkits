package io.github.mbackschat.a12.dm.cli.rule;

import io.github.mbackschat.a12.dm.cli.CliTestSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * {@code rule test} (FEATURE-GAPS) — the scoped, suppression-aware runtime polarity check for one rule
 * against a document INSTANCE. It must distinguish fired / passed / **suppressed** (a rule the kernel skipped
 * because a referenced field is formally invalid — reporting that as a pass would mislead), and refuse a name
 * that isn't a validation rule. The fixture rule is {@code FeeCapRule}: fires when {@code MonthlyFee > 1000}.
 */
class RuleTestVerbTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static Path doc(Path dir, String monthlyFee) throws Exception {
        Path file = dir.resolve("doc.json");
        Files.writeString(file, "{\"fields\":{\"Subscription\":{\"Addons\":[{\"MonthlyFee\":\"" + monthlyFee + "\"}]}}}");
        return file;
    }

    @Test
    void firesOnAViolatingInstance(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.modelWithRule(dir);
        CliTestSupport.Result res = CliTestSupport.runKernel("-m", model.toString(), "rule", "test",
                CliTestSupport.RULE_FULL_NAME, "--doc", doc(dir, "2000").toString());
        assertEquals(0, res.code(), res::err);
        JsonNode data = MAPPER.readTree(res.out()).path("data");
        assertEquals("fired", data.path("verdict").asText(), res::out);
        assertTrue(data.path("fired").asBoolean());
        // G1: a fired rule surfaces the interpolated end-user message (here a token-free literal).
        assertEquals("Too high.", data.path("message").asText(), res::out);
    }

    @Test
    void passesOnASatisfyingInstance(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.modelWithRule(dir);
        CliTestSupport.Result res = CliTestSupport.runKernel("-m", model.toString(), "rule", "test",
                CliTestSupport.RULE_FULL_NAME, "--doc", doc(dir, "10").toString());
        assertEquals(0, res.code(), res::err);
        JsonNode data = MAPPER.readTree(res.out()).path("data");
        assertEquals("passed", data.path("verdict").asText(), res::out);
        assertFalse(data.path("fired").asBoolean());
    }

    @Test
    void reportsSuppressionWhenAReferencedFieldIsFormallyInvalid(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.modelWithRule(dir);
        // MonthlyFee is a 2-decimal field; 3 decimals make it formally invalid → FeeCapRule never runs.
        CliTestSupport.Result res = CliTestSupport.runKernel("-m", model.toString(), "rule", "test",
                CliTestSupport.RULE_FULL_NAME, "--doc", doc(dir, "2000.999").toString());
        assertEquals(0, res.code(), res::err);
        JsonNode data = MAPPER.readTree(res.out()).path("data");
        assertEquals("suppressed", data.path("verdict").asText(), res::out);
        assertTrue(data.path("suppressedBy").isArray() && data.path("suppressedBy").size() > 0,
                () -> "names the suppressing field: " + res.out());
        assertTrue(data.path("suppressedBy").get(0).path("field").asText().endsWith("MonthlyFee"), res::out);
    }

    @Test
    void rejectsAComputationName(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.modelWithRuleAndComputation(dir);
        CliTestSupport.Result res = CliTestSupport.runKernel("-m", model.toString(), "rule", "test",
                CliTestSupport.COMPUTATION_FULL_NAME, "--doc", doc(dir, "10").toString());
        assertEquals(2, res.code(), "a computation is not a validation rule — refuse");
    }
}
