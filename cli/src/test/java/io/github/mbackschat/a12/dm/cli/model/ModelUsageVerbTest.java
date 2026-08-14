package io.github.mbackschat.a12.dm.cli.model;

import io.github.mbackschat.a12.dm.cli.CliTestSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The {@code model usage} verb (FEATURE-GAPS G9) — the model-wide usage/audit read: every field's
 * referencing rules/computations + the unreferenced set, in ONE call. Replaces the {@code where-used}×N
 * loop a maintainer/agent had to write by hand. Built on the same {@code getDirectlyReferencedEntities}
 * primitive {@code where-used} uses, but it answers the wider REACHABILITY question — a field read only through
 * an ancestor group operand counts as read — so it deliberately differs from {@code where-used} on exactly those
 * fields ({@code CG16}; pinned by {@code ModelUsageGroupOperandTest}).
 */
class ModelUsageVerbTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void mapsEveryFieldToItsReferrersAndListsTheUnreferenced(@TempDir Path dir) throws Exception {
        Path model = dir.resolve("m.dm.json");
        Files.writeString(model, CliTestSupport.modelWithRuleAndComputationJson());
        var res = CliTestSupport.run("-m", model.toString(), "model", "usage");
        assertEquals(0, res.code(), res::err);

        JsonNode data = MAPPER.readTree(res.out()).path("data");
        JsonNode usage = data.path("usage");
        // model-wide: every field appears, not just the referenced ones
        assertTrue(usage.size() > 2, () -> "usage maps every field (model-wide): " + res.out());

        // a field referenced by a validation rule
        boolean feeByRule = false;
        for (JsonNode r : usage.path("/Subscription/Addons/MonthlyFee")) {
            feeByRule |= CliTestSupport.RULE_FULL_NAME.equals(r.asText());
        }
        assertTrue(feeByRule, () -> "MonthlyFee → FeeCapRule: " + res.out());

        // a field referenced ONLY by a computation (the where-used computations leg)
        boolean baseByComp = false;
        for (JsonNode r : usage.path("/Subscription/Billing/BaseFee")) {
            baseByComp |= CliTestSupport.COMPUTATION_FULL_NAME.equals(r.asText());
        }
        assertTrue(baseByComp, () -> "BaseFee → the computation: " + res.out());

        // the unreferenced set never contains a referenced field
        JsonNode unreferenced = data.path("unreferenced");
        assertTrue(unreferenced.isArray(), () -> "unreferenced is an array: " + res.out());
        for (JsonNode u : unreferenced) {
            assertNotEquals("/Subscription/Addons/MonthlyFee", u.asText(),
                    "a referenced field is never listed as unreferenced");
        }
    }

    @Test
    void ridesTheResultEnvelope(@TempDir Path dir) throws Exception {
        Path model = dir.resolve("m.dm.json");
        Files.writeString(model, CliTestSupport.modelWithRuleJson());
        var res = CliTestSupport.run("-m", model.toString(), "model", "usage");
        assertEquals(0, res.code(), res::err);
        JsonNode j = MAPPER.readTree(res.out());
        assertEquals("model", j.path("target").asText());
        assertEquals("usage", j.path("op").asText());
        assertEquals("read", j.path("outcome").asText());
    }
}
