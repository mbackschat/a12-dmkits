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
 * The {@code rule read} / {@code model read} verbs — read an existing rule's condition (or the whole
 * model) back into rulekit's AST via the adapter (F10). Tolerant: an unmodeled construct returns {@code
 * converted:false} with the verbatim text, not an error.
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
        var res = CliTestSupport.run("-m", modelWithRule(dir).toString(),
                "rule", "read", CliTestSupport.RULE_FULL_NAME);
        assertEquals(0, res.code(), res::err);
        JsonNode data = MAPPER.readTree(res.out()).path("data");
        assertTrue(data.get("converted").asBoolean(), "a modeled condition converts");
        String condition = data.get("condition").asText();
        assertTrue(condition.contains("MonthlyFee") && condition.contains("1000"),
                () -> "round-trips to a renderable condition: " + condition);
    }

    @Test
    void surfacesTheRulesStoredMessages(@TempDir Path dir) throws Exception {
        // G10: read back what a rule SAYS (locale → text), raw + uninterpolated — no runtime-eval verb.
        // The `rule read <path>` subcommand rides the result envelope, so messages live under `data`.
        var res = CliTestSupport.run("-m", modelWithRule(dir).toString(),
                "rule", "read", CliTestSupport.RULE_FULL_NAME);
        assertEquals(0, res.code(), res::err);
        JsonNode messages = MAPPER.readTree(res.out()).path("data").path("messages");
        assertTrue(messages.isArray() && messages.size() == 2, () -> "both declared locales surfaced: " + res.out());
        boolean foundEn = false;
        for (JsonNode m : messages) {
            foundEn |= "Too high.".equals(m.path("text").asText());
            assertTrue(!m.path("locale").asText().isBlank(), () -> "each message names its locale: " + m);
        }
        assertTrue(foundEn, () -> "the stored message text is read back raw: " + res.out());
    }

    // No-arg `rule read` now LISTS the rule full-names (the unified read contract: no path = list everything
    // in scope) — it no longer errors. Locked by RuleVerbTest.readWithNoPathListsTheRuleNames; not re-asserted
    // here to avoid duplication.

    @Test
    void allReadsRulesAndComputationsAsDisjointSections(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("m.dm.json");
        Files.writeString(f, CliTestSupport.modelWithRuleAndComputationJson());
        var res = CliTestSupport.run("-m", f.toString(), "model", "read");
        assertEquals(0, res.code(), res::err);
        JsonNode data = MAPPER.readTree(res.out()).path("data");
        assertTrue(data.path("rules").has(CliTestSupport.RULE_FULL_NAME), () -> "rule section: " + res.out());
        assertTrue(data.path("computations").has(CliTestSupport.COMPUTATION_FULL_NAME),
                () -> "computation section: " + res.out());
        // the computation is NOT listed as a rule (disjoint)
        assertTrue(data.path("rules").path(CliTestSupport.COMPUTATION_FULL_NAME).isMissingNode(),
                () -> "the computation must not appear under rules: " + res.out());
        assertTrue(data.path("summary").get("fullyTyped").asBoolean(), () -> "every element typed: " + res.out());
        assertEquals(CliTestSupport.COMPUTATION_FULL_NAME, computationKey(data),
                () -> "the computation reports its calculated field: " + res.out());
    }

    @Test
    void ruleReadOnAComputationRedirects(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("m.dm.json");
        Files.writeString(f, CliTestSupport.modelWithRuleAndComputationJson());
        var res = CliTestSupport.run("-m", f.toString(), "rule", "read", CliTestSupport.COMPUTATION_FULL_NAME);
        assertNotEquals(0, res.code(), "a computation is not a validation rule");
        assertTrue(res.err().contains("computation") && res.err().contains("--all"),
                () -> "redirects to the computation-aware verbs: " + res.err());
    }

    private static String computationKey(JsonNode data) {
        JsonNode comps = data.path("computations");
        return comps.fieldNames().hasNext() ? comps.fieldNames().next() : "";
    }
}
