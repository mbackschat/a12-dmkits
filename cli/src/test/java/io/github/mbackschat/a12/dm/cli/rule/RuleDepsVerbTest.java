package io.github.mbackschat.a12.dm.cli.rule;

import io.github.mbackschat.a12.dm.cli.CliTestSupport;

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

    /**
     * {@code CG16}'s last item, MEASURED rather than reasoned. The ledger recorded a suspicion that
     * {@code rule deps} and {@code where-used} <i>disagree</i> over a rule whose operand is a GROUP, because
     * {@code deps} projects the CONVERTED AST while {@code where-used} reads the kernel primitive. Half of it
     * was settled by reading — the two are backed by different sources — but whether they produce different
     * ANSWERS was never probed, and a suspicion nobody runs is how a wrong claim survives.
     *
     * <p><b>They do differ, and correctly.</b> A group-operand rule's forward dependency IS the group: that is
     * what its condition names, and {@code deps} says so. {@code where-used} on a field inside that group
     * lists nothing, because it answers the exact question — which rules NAME this entity — and none does.
     * Neither is wrong; they are inverses of different questions, and only the reachability reader
     * ({@code model usage}, {@code KernelAdapter.referrersReaching}) closes the loop. The row asserts both
     * halves so the pair cannot silently drift into actually contradicting each other.
     */
    @Test
    void depsNamesTheGroupWhileWhereUsedNamesNothingForAFieldInsideIt(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.groupOperandRuled(dir);

        var deps = CliTestSupport.run("-m", model.toString(), "rule", "deps", "/Policy/AssessmentComplete");
        assertEquals(0, deps.code(), deps.err());
        Set<String> referenced = new HashSet<>();
        for (JsonNode r : M.readTree(deps.out()).get("data").get("references")) {
            referenced.add(r.get("path").asText());
        }
        assertTrue(referenced.contains("/Policy/Assessment"),
                () -> "the condition names the GROUP; that is the forward dependency: " + deps.out());
        assertFalse(referenced.contains("/Policy/Assessment/Observed"),
                () -> "deps projects what the condition names, not what the kernel expands it to: " + deps.out());

        var used = CliTestSupport.run("-m", model.toString(), "where-used", "/Policy/Assessment/Observed");
        assertEquals(0, used.code(), used.err());
        assertFalse(used.out().contains("AssessmentComplete"),
                () -> "where-used is EXACT by design: no rule names this field: " + used.out());

        // POSITIVE CONTROL — without it the row above passes just as well when where-used finds nothing ever.
        var named = CliTestSupport.run("-m", model.toString(), "where-used", "/Policy/Reference");
        assertEquals(0, named.code(), named.err());
        assertTrue(named.out().contains("AssessmentComplete"),
                () -> "the same rule DOES name /Policy/Reference directly: " + named.out());
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
