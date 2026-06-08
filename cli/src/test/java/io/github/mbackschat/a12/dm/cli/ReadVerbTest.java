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
 * The {@code show} verb — read an existing rule's condition back into rulekit's AST via the adapter
 * (F10). Tolerant: an unmodeled construct returns {@code converted:false} with the verbatim text, not
 * an error.
 */
class ReadVerbTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** A subscription model carrying the FeeCapRule (`[MonthlyFee] > 1000`), written to a temp file. */
    private static Path modelWithRule(Path dir) throws IOException {
        Path f = dir.resolve("m.dm.json");
        Files.writeString(f, CliTestSupport.modelWithRuleJson());
        return f;
    }

    @Test
    void readsAnExistingRuleAsARenderableCondition(@TempDir Path dir) throws Exception {
        var res = CliTestSupport.run("read", modelWithRule(dir).toString(),
                "--rule", CliTestSupport.RULE_FULL_NAME);
        assertEquals(0, res.code(), res::err);
        JsonNode j = MAPPER.readTree(res.out());
        assertTrue(j.get("converted").asBoolean(), "a modeled condition converts");
        String condition = j.get("condition").asText();
        assertTrue(condition.contains("MonthlyFee") && condition.contains("1000"),
                () -> "round-trips to a renderable condition: " + condition);
    }

    @Test
    void missingModeFails(@TempDir Path dir) throws Exception {
        var res = CliTestSupport.run("read", modelWithRule(dir).toString());
        assertNotEquals(0, res.code(), "read needs --rule or --all");
        assertTrue(res.err().toLowerCase().contains("rule") && res.err().contains("--all"),
                () -> "names the two modes: " + res.err());
    }

    @Test
    void bothModesFail(@TempDir Path dir) throws Exception {
        var res = CliTestSupport.run("read", modelWithRule(dir).toString(),
                "--rule", CliTestSupport.RULE_FULL_NAME, "--all");
        assertNotEquals(0, res.code(), "--rule and --all are mutually exclusive");
    }

    @Test
    void allReadsRulesAndComputationsAsDisjointSections(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("m.dm.json");
        Files.writeString(f, CliTestSupport.modelWithRuleAndComputationJson());
        var res = CliTestSupport.run("read", f.toString(), "--all");
        assertEquals(0, res.code(), res::err);
        JsonNode j = MAPPER.readTree(res.out());
        assertTrue(j.path("rules").has(CliTestSupport.RULE_FULL_NAME), () -> "rule section: " + res.out());
        assertTrue(j.path("computations").has(CliTestSupport.COMPUTATION_FULL_NAME),
                () -> "computation section: " + res.out());
        // the computation is NOT listed as a rule (disjoint)
        assertTrue(j.path("rules").path(CliTestSupport.COMPUTATION_FULL_NAME).isMissingNode(),
                () -> "the computation must not appear under rules: " + res.out());
        assertTrue(j.path("summary").get("fullyTyped").asBoolean(), () -> "every element typed: " + res.out());
        assertEquals(CliTestSupport.COMPUTATION_FULL_NAME, computationKey(j),
                () -> "the computation reports its calculated field: " + res.out());
    }

    @Test
    void ruleFlagOnAComputationRedirects(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("m.dm.json");
        Files.writeString(f, CliTestSupport.modelWithRuleAndComputationJson());
        var res = CliTestSupport.run("read", f.toString(), "--rule", CliTestSupport.COMPUTATION_FULL_NAME);
        assertNotEquals(0, res.code(), "a computation is not a validation rule");
        assertTrue(res.err().contains("computation") && res.err().contains("--all"),
                () -> "redirects to the computation-aware verbs: " + res.err());
    }

    private static String computationKey(JsonNode j) {
        JsonNode comps = j.path("computations");
        return comps.fieldNames().hasNext() ? comps.fieldNames().next() : "";
    }
}
