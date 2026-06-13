package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * {@code rule format} / {@code computation format} (FEATURE-GAPS §3.2) — the kernel's canonical condition
 * text, optionally translated EN↔DE, on the read envelope's {@code data}. The canonical form is the engine's
 * own (a parse + format round-trip through its formatter); each verb refuses a name of the wrong element kind.
 */
class FormatVerbTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void ruleFormatEmitsTheKernelCanonicalText(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.modelWithRule(dir);
        CliTestSupport.Result res = CliTestSupport.run("-m", model.toString(), "rule", "format",
                CliTestSupport.RULE_FULL_NAME);
        assertEquals(0, res.code(), res::err);
        JsonNode j = MAPPER.readTree(res.out());
        assertEquals("read", j.path("outcome").asText());
        JsonNode data = j.path("data");
        assertEquals(CliTestSupport.RULE_FULL_NAME, data.path("rule").asText());
        assertEquals("model", data.path("lang").asText(), "no --lang reports the model's own language");
        assertTrue(data.path("canonical").asText().contains("MonthlyFee"),
                () -> "the canonical text references the field, got: " + res.out());
    }

    @Test
    void ruleFormatTranslatesToGerman(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.modelWithRule(dir);
        CliTestSupport.Result res = CliTestSupport.run("-m", model.toString(), "rule", "format",
                CliTestSupport.RULE_FULL_NAME, "--lang", "DE");
        assertEquals(0, res.code(), res::err);
        JsonNode data = MAPPER.readTree(res.out()).path("data");
        assertEquals("de", data.path("lang").asText());
        // the fixture rule's condition ([MonthlyFee] > 1000) is symbolic, so the field path is what survives
        assertTrue(data.path("canonical").asText().contains("MonthlyFee"), res::out);
    }

    @Test
    void computationFormatEmitsCanonicalText(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.modelWithRuleAndComputation(dir);
        CliTestSupport.Result res = CliTestSupport.run("-m", model.toString(), "computation", "format",
                CliTestSupport.COMPUTATION_FULL_NAME);
        assertEquals(0, res.code(), res::err);
        JsonNode data = MAPPER.readTree(res.out()).path("data");
        assertEquals(CliTestSupport.COMPUTATION_FULL_NAME, data.path("computation").asText());
        assertFalse(data.path("canonical").asText().isBlank(),
                () -> "the computation's canonical text is non-empty, got: " + res.out());
    }

    @Test
    void ruleFormatRejectsAComputationName(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.modelWithRuleAndComputation(dir);
        CliTestSupport.Result res = CliTestSupport.run("-m", model.toString(), "rule", "format",
                CliTestSupport.COMPUTATION_FULL_NAME);
        assertEquals(2, res.code(), "a computation is not a validation rule — refuse");
    }

    @Test
    void computationFormatRejectsARuleName(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.modelWithRuleAndComputation(dir);
        CliTestSupport.Result res = CliTestSupport.run("-m", model.toString(), "computation", "format",
                CliTestSupport.RULE_FULL_NAME);
        assertEquals(2, res.code(), "a validation rule is not a computation — refuse");
    }
}
