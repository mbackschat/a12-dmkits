package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code rule deps} — a rule's forward dependencies (the fields/groups its condition references + iteration),
 * the thin projection of {@code rule explain} and the inverse of {@code where-used}.
 */
class RuleDepsVerbTest {

    private static final ObjectMapper M = new ObjectMapper();

    @Test
    void listsAModeledRulesReferencedFieldsWithKinds(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);
        // DeliveryNotBeforeOrder: AllFieldsFilled(OrderDate, DeliveryDate) And DifferenceInDays(...) < 0
        var res = CliTestSupport.run("-m", model.toString(), "rule", "deps", "/Order/DeliveryNotBeforeOrder");
        assertEquals(0, res.code(), res.err());
        JsonNode j = M.readTree(res.out());
        assertEquals("read", j.get("outcome").asText(), () -> "a read envelope: " + res.out());
        JsonNode data = j.get("data");

        Set<String> paths = new HashSet<>();
        Map<String, String> kinds = new HashMap<>();
        for (JsonNode r : data.get("references")) {
            paths.add(r.get("path").asText());
            kinds.put(r.get("path").asText(), r.get("kind").asText());
        }
        assertTrue(paths.containsAll(Set.of("/Order/OrderDate", "/Order/DeliveryDate")),
                () -> "the two date fields its condition references: " + data);
        assertEquals("DATE", kinds.get("/Order/DeliveryDate"), "each reference carries its model kind");
        assertEquals(paths.size(), data.get("count").asInt(), "count matches the references array");
        assertFalse(data.get("iterates").asBoolean(), "this rule does not iterate a repeatable group");
    }

    @Test
    void anUnknownRuleIsACodedRefusalNotABareError(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);
        var res = CliTestSupport.run("-m", model.toString(), "rule", "deps", "/Order/NoSuchRule");
        assertNotEquals(0, res.code(), "an unknown rule path is refused");
        JsonNode j = M.readTree(res.out());
        assertEquals("refused", j.get("outcome").asText(), () -> "a structured refusal, not a bare error: " + res.out());
        assertFalse(j.path("diagnostics").path(0).path("code").asText().isBlank(),
                () -> "the refusal carries a code: " + res.out());
    }

    @Test
    void anOpaqueConditionIsTolerantNeverAnError(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);
        // EligibilityCheck rides a host-delegated `CustomCondition` the converter may not model — must stay
        // a clean read (tolerant `explained:false`), not an error envelope.
        var res = CliTestSupport.run("-m", model.toString(), "rule", "deps", "/Order/EligibilityCheck");
        assertEquals(0, res.code(), res.err());
        JsonNode data = M.readTree(res.out()).get("data");
        assertTrue(data.has("references") || data.path("explained").asBoolean(true) == false,
                () -> "either modeled (references) or a tolerant explained:false: " + data);
    }
}
