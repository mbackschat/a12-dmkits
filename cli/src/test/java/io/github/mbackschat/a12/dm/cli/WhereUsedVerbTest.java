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
 * reference a given field/group. Backed by the kernel's {@code getDirectlyReferencedEntities}.
 */
class WhereUsedVerbTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static Path modelWithRule(Path dir) throws IOException {
        Path f = dir.resolve("m.dm.json");
        Files.writeString(f, CliTestSupport.modelWithRuleJson());
        return f;
    }

    @Test
    void listsTheRuleThatReferencesTheField(@TempDir Path dir) throws Exception {
        var res = CliTestSupport.run("where-used", modelWithRule(dir).toString(),
                "--entity", "/Subscription/Addons/MonthlyFee");
        assertEquals(0, res.code(), res::err);
        JsonNode j = MAPPER.readTree(res.out());
        assertEquals("/Subscription/Addons/MonthlyFee", j.get("entity").asText());
        boolean found = false;
        for (JsonNode r : j.path("rules")) {
            found |= CliTestSupport.RULE_FULL_NAME.equals(r.asText());
        }
        assertTrue(found, () -> "where-used should list " + CliTestSupport.RULE_FULL_NAME + ": " + res.out());
        assertEquals(1, j.get("count").asInt());
    }

    @Test
    void anUnreferencedEntityYieldsAnEmptyList(@TempDir Path dir) throws Exception {
        var res = CliTestSupport.run("where-used", modelWithRule(dir).toString(),
                "--entity", "/Subscription/NoSuchField");
        assertEquals(0, res.code(), res::err);
        JsonNode j = MAPPER.readTree(res.out());
        assertEquals(0, j.get("count").asInt(), () -> "no rule references it: " + res.out());
    }

    @Test
    void missingEntityFlagFails(@TempDir Path dir) throws Exception {
        var res = CliTestSupport.run("where-used", modelWithRule(dir).toString());
        assertNotEquals(0, res.code(), "where-used needs --entity");
    }

    @Test
    void surfacesComputationsSeparatelyFromRules(@TempDir Path dir) throws Exception {
        Path model = dir.resolve("m.dm.json");
        Files.writeString(model, CliTestSupport.modelWithRuleAndComputationJson());
        // BaseFee is read only by the computation EffectiveFee = BaseFee — no validation rule touches it
        var res = CliTestSupport.run("where-used", model.toString(),
                "--entity", "/Subscription/Billing/BaseFee");
        assertEquals(0, res.code(), res::err);
        JsonNode j = MAPPER.readTree(res.out());
        assertTrue(j.path("rules").isEmpty(), () -> "no validation rule references BaseFee: " + res.out());
        boolean inComputations = false;
        for (JsonNode c : j.path("computations")) {
            inComputations |= CliTestSupport.COMPUTATION_FULL_NAME.equals(c.asText());
        }
        assertTrue(inComputations,
                () -> "where-used should list the computation under 'computations': " + res.out());
        assertEquals(1, j.get("count").asInt(), () -> "count spans rules + computations: " + res.out());
    }
}
