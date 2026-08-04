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
 * {@code rule eval} (FEATURE-GAPS) — the scoped, suppression-aware runtime polarity check for one rule
 * against a document INSTANCE. It must distinguish fired / passed / **suppressed** (a rule the kernel skipped
 * because a referenced field is formally invalid — reporting that as a pass would mislead), and refuse a name
 * that isn't a validation rule. The fixture rule is {@code FeeCapRule}: fires when {@code MonthlyFee > 1000}.
 */
class RuleTestVerbTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static Path doc(Path dir, String monthlyFee) throws Exception {
        Path file = dir.resolve("doc.json");
        Files.writeString(file, "{\"Subscription\":{\"Addons\":[{\"MonthlyFee\":\"" + monthlyFee + "\"}]}}");
        return file;
    }

    @Test
    void firesOnAViolatingInstance(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.modelWithRule(dir);
        CliTestSupport.Result res = CliTestSupport.runKernel("-m", model.toString(), "rule", "eval",
                CliTestSupport.RULE_FULL_NAME, "--instance", doc(dir, "2000").toString());
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
        CliTestSupport.Result res = CliTestSupport.runKernel("-m", model.toString(), "rule", "eval",
                CliTestSupport.RULE_FULL_NAME, "--instance", doc(dir, "10").toString());
        assertEquals(0, res.code(), res::err);
        JsonNode data = MAPPER.readTree(res.out()).path("data");
        assertEquals("passed", data.path("verdict").asText(), res::out);
        assertFalse(data.path("fired").asBoolean());
    }

    @Test
    void reportsSuppressionWhenAReferencedFieldIsFormallyInvalid(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.modelWithRule(dir);
        // MonthlyFee is a 2-decimal field; 3 decimals make it formally invalid → FeeCapRule never runs.
        CliTestSupport.Result res = CliTestSupport.runKernel("-m", model.toString(), "rule", "eval",
                CliTestSupport.RULE_FULL_NAME, "--instance", doc(dir, "2000.999").toString());
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
        CliTestSupport.Result res = CliTestSupport.runKernel("-m", model.toString(), "rule", "eval",
                CliTestSupport.COMPUTATION_FULL_NAME, "--instance", doc(dir, "10").toString());
        assertEquals(2, res.code(), "a computation is not a validation rule — refuse");
    }

    @Test
    void evaluatesACandidateWithoutPersistingIt(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);
        String before = Files.readString(model);
        Path instance = dir.resolve("order.json");
        Files.writeString(instance, "{\"Order\":{\"Quantity\":\"150\"}}");

        CliTestSupport.Result res = CliTestSupport.run("-m", model.toString(), "rule", "eval",
                "--condition", "[Quantity] > 100", "--field", "/Order/Quantity",
                "--instance", instance.toString());

        assertEquals(0, res.code(), () -> res.out() + res.err());
        JsonNode envelope = MAPPER.readTree(res.out());
        assertEquals("read", envelope.path("outcome").asText(), res::out);
        assertEquals("DM_INTERPRETER", envelope.path("engine").asText(), res::out);
        assertEquals("fired", envelope.path("data").path("verdict").asText(), res::out);
        assertEquals("/Order/EvalRuleCandidate", envelope.path("data").path("rule").asText(), res::out);
        assertEquals(before, Files.readString(model), "candidate evaluation must leave the model byte-identical");
    }

    @Test
    void candidateModeReportsPassedAndAcceptsAnOptionalCode(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);
        Path instance = dir.resolve("order.json");
        Files.writeString(instance, "{\"Order\":{\"Quantity\":\"50\"}}");

        CliTestSupport.Result res = CliTestSupport.run("-m", model.toString(), "rule", "eval",
                "--condition", "[Quantity] > 100", "--field", "/Order/Quantity", "--code", "QTY_CAP",
                "--instance", instance.toString());

        assertEquals(0, res.code(), () -> res.out() + res.err());
        JsonNode data = MAPPER.readTree(res.out()).path("data");
        assertEquals("passed", data.path("verdict").asText(), res::out);

        Files.writeString(instance, "{\"Order\":{\"Quantity\":\"150\"}}");
        CliTestSupport.Result fired = CliTestSupport.run("-m", model.toString(), "rule", "eval",
                "--condition", "[Quantity] > 100", "--field", "/Order/Quantity", "--code", "QTY_CAP",
                "--instance", instance.toString());
        assertEquals("QTY_CAP", MAPPER.readTree(fired.out()).path("data").path("message").asText(),
                "--code must determine the candidate's observable fallback message");
    }

    @Test
    void storedAndCandidateModesAreMutuallyExclusiveAndCandidateFlagsArePaired(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);
        Path instance = dir.resolve("order.json");
        Files.writeString(instance, "{\"Order\":{\"Quantity\":\"50\"}}");

        CliTestSupport.Result mixed = CliTestSupport.run("-m", model.toString(), "rule", "eval",
                "/Order/EligibilityCheck", "--condition", "[Quantity] > 100", "--field", "/Order/Quantity",
                "--instance", instance.toString());
        CliTestSupport.Result unpaired = CliTestSupport.run("-m", model.toString(), "rule", "eval",
                "--condition", "[Quantity] > 100", "--instance", instance.toString());

        assertEquals(2, mixed.code(), () -> mixed.out() + mixed.err());
        assertEquals(2, unpaired.code(), () -> unpaired.out() + unpaired.err());
        assertTrue(mixed.err().contains("either") || mixed.out().contains("either"),
                () -> mixed.out() + mixed.err());
        assertTrue(unpaired.err().contains("both") || unpaired.out().contains("both"),
                () -> unpaired.out() + unpaired.err());
    }
}
