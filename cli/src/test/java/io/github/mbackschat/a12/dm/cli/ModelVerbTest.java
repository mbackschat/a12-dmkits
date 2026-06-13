package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The target-first {@code doc} group (CLI-SPEC §5) — the first verb migrated to the new surface, proving
 * the three mechanics the whole sweep rides on: the nested command tree (root → {@code doc} → {@code
 * validate}), the {@code -m/--model} context option accepted <b>before or after</b> the verb, and the
 * uniform result envelope (§3b). Subject validity rides {@code valid}; the op executing rides {@code ok}.
 */
class ModelVerbTest {

    private static final ObjectMapper M = new ObjectMapper();

    @Test
    void validatesAValidModelWithModelBeforeTheVerb(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.modelWithRule(dir);
        var res = CliTestSupport.run("-m", model.toString(), "model", "validate");
        assertEquals(0, res.code(), res.err());
        JsonNode j = M.readTree(res.out());
        assertEquals("model", j.get("target").asText());
        assertEquals("validate", j.get("op").asText());
        assertEquals("read", j.get("outcome").asText());
        assertTrue(j.get("ok").asBoolean());
        assertTrue(j.get("valid").asBoolean());
        assertTrue(j.path("diagnostics").isArray());
    }

    @Test
    void acceptsTheModelOptionAfterTheVerbToo(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.modelWithRule(dir);
        var res = CliTestSupport.run("model", "validate", "-m", model.toString());
        assertEquals(0, res.code(), res.err());
        assertTrue(M.readTree(res.out()).get("valid").asBoolean());
    }

    @Test
    void describeCarriesTheModelCardInData(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.modelWithRule(dir);
        JsonNode card = M.readTree(CliTestSupport.run("describe", model.toString()).out());
        var res = CliTestSupport.run("-m", model.toString(), "model", "describe");
        assertEquals(0, res.code(), res.err());
        JsonNode j = M.readTree(res.out());
        assertEquals("model", j.get("target").asText());
        assertEquals("describe", j.get("op").asText());
        assertEquals("read", j.get("outcome").asText());
        assertTrue(j.get("ok").asBoolean());
        assertEquals(card, j.get("data"), "model describe carries the model card in `data`");
    }

    @Test
    void describeCardSurfacesDeclaredLocales(@TempDir Path dir) throws Exception {
        // The model card is the agent's first orientation surface — it carries the declared message
        // locales so an agent provisions a rule/computation message per locale (the per-locale-message
        // law, MVK_ERROR_MESSAGE_FOR_LANGUAGE_MISSING) before authoring, not after a failure.
        Path model = CliTestSupport.modelWithRule(dir); // subscription fixture: declares en_US + de_DE
        var res = CliTestSupport.run("-m", model.toString(), "model", "describe");
        assertEquals(0, res.code(), res.err());
        JsonNode locales = M.readTree(res.out()).path("data").path("locales");
        assertTrue(locales.isArray(), () -> "the model card declares `locales`: " + res.out());
        assertEquals(2, locales.size());
        assertEquals("en_US", locales.get(0).asText());
        assertEquals("de_DE", locales.get(1).asText());
    }

    @Test
    void readReturnsTheWholeModelInData(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.modelWithRuleAndComputation(dir);
        var res = CliTestSupport.run("-m", model.toString(), "model", "read");
        assertEquals(0, res.code(), res.err());
        JsonNode data = M.readTree(res.out()).get("data");
        assertTrue(data.has("rules") && data.has("computations") && data.has("summary"),
                "model read carries the whole-model read in `data`");
    }

    @Test
    void evalReportsFiredRulesInData(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.modelWithRule(dir);
        Path docFile = dir.resolve("doc.json");
        Files.writeString(docFile, "{ \"fields\": { \"Subscription\": { \"Addons\": [ { \"MonthlyFee\": \"2000\" } ] } } }");
        var res = CliTestSupport.run("-m", model.toString(), "model", "eval", "--doc", docFile.toString());
        assertEquals(0, res.code(), res.err());
        JsonNode data = M.readTree(res.out()).get("data");
        assertTrue(data.has("fired") && data.has("messages"), "model eval carries fired/messages in `data`");
    }

    @Test
    void computeReportsComputedFieldsInData(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.modelWithRuleAndComputation(dir);
        Path docFile = dir.resolve("compute.json");
        Files.writeString(docFile, "{ \"fields\": { \"Subscription\": { \"Billing\": { \"BaseFee\": \"50\" } } } }");
        var res = CliTestSupport.run("-m", model.toString(), "model", "compute", "--doc", docFile.toString());
        assertEquals(0, res.code(), res.err());
        assertTrue(M.readTree(res.out()).get("data").has("computed"), "model compute carries computed[] in `data`");
    }
}
