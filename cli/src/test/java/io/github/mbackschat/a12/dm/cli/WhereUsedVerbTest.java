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
 * The {@code where-used} verb (F9a) — the exact inverse of a rule's referenced entities: which rules
 * reference a given field/group. Backed by the kernel's {@code getDirectlyReferencedEntities}. The model
 * is the {@code -m} context (like every other read verb); the entity is positional (G11) — {@code --entity}
 * stays as the explicit alias.
 */
class WhereUsedVerbTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static Path modelWithRule(Path dir) throws IOException {
        Path f = dir.resolve("m.dm.json");
        Files.writeString(f, CliTestSupport.modelWithRuleJson());
        return f;
    }

    @Test
    void listsTheRuleThatReferencesThePositionalEntity(@TempDir Path dir) throws Exception {
        var res = CliTestSupport.run("-m", modelWithRule(dir).toString(),
                "where-used", "/Subscription/Addons/MonthlyFee");
        assertEquals(0, res.code(), res::err);
        JsonNode j = MAPPER.readTree(res.out());
        // where-used rides the result envelope — its payload is under `data` (consistent with model usage)
        assertEquals("where-used", j.path("target").asText());
        assertEquals("read", j.path("op").asText());
        assertEquals("read", j.path("outcome").asText());
        JsonNode data = j.path("data");
        assertEquals("/Subscription/Addons/MonthlyFee", data.path("entity").asText());
        boolean found = false;
        for (JsonNode r : data.path("rules")) {
            found |= CliTestSupport.RULE_FULL_NAME.equals(r.asText());
        }
        assertTrue(found, () -> "where-used should list " + CliTestSupport.RULE_FULL_NAME + ": " + res.out());
        assertEquals(1, data.path("count").asInt());
    }

    @Test
    void acceptsTheEntityViaExplicitFlagToo(@TempDir Path dir) throws Exception {
        // --entity stays as the explicit alias for the positional entity
        var res = CliTestSupport.run("-m", modelWithRule(dir).toString(),
                "where-used", "--entity", "/Subscription/Addons/MonthlyFee");
        assertEquals(0, res.code(), res::err);
        assertEquals("/Subscription/Addons/MonthlyFee",
                MAPPER.readTree(res.out()).path("data").path("entity").asText());
    }

    @Test
    void anUnreferencedEntityYieldsAnEmptyList(@TempDir Path dir) throws Exception {
        var res = CliTestSupport.run("-m", modelWithRule(dir).toString(),
                "where-used", "/Subscription/NoSuchField");
        assertEquals(0, res.code(), res::err);
        JsonNode j = MAPPER.readTree(res.out());
        assertEquals(0, j.path("data").path("count").asInt(), () -> "no rule references it: " + res.out());
    }

    @Test
    void missingEntityFails(@TempDir Path dir) throws Exception {
        var res = CliTestSupport.run("-m", modelWithRule(dir).toString(), "where-used");
        assertNotEquals(0, res.code(), "where-used needs an entity (positional or --entity)");
    }

    @Test
    void theOldModelPositionalFormSelfCorrects(@TempDir Path dir) throws Exception {
        // the agent fumble: where-used <model> --entity=… (model passed positionally, no -m) — must name the
        // change (model is now -m) rather than fail opaquely
        var res = CliTestSupport.run("where-used", modelWithRule(dir).toString(),
                "--entity", "/Subscription/Addons/MonthlyFee");
        assertNotEquals(0, res.code(), "the old model-positional form no longer works");
        assertTrue(res.err().contains("-m") && res.err().contains("positional"),
                () -> "the error names the new -m + positional <entity> form: " + res.err());
    }

    @Test
    void surfacesComputationsSeparatelyFromRules(@TempDir Path dir) throws Exception {
        Path model = dir.resolve("m.dm.json");
        Files.writeString(model, CliTestSupport.modelWithRuleAndComputationJson());
        // BaseFee is read only by the computation EffectiveFee = BaseFee — no validation rule touches it
        var res = CliTestSupport.run("-m", model.toString(),
                "where-used", "/Subscription/Billing/BaseFee");
        assertEquals(0, res.code(), res::err);
        JsonNode data = MAPPER.readTree(res.out()).path("data");
        assertTrue(data.path("rules").isEmpty(), () -> "no validation rule references BaseFee: " + res.out());
        boolean inComputations = false;
        for (JsonNode c : data.path("computations")) {
            inComputations |= CliTestSupport.COMPUTATION_FULL_NAME.equals(c.asText());
        }
        assertTrue(inComputations,
                () -> "where-used should list the computation under 'computations': " + res.out());
        assertEquals(1, data.path("count").asInt(), () -> "count spans rules + computations: " + res.out());
    }
}
