package io.github.mbackschat.a12.dm.cli.computation;

import io.github.mbackschat.a12.dm.cli.CliTestSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The target-first {@code computation} group (CLI-SPEC §5) — the rulekit computation lifecycle: add (from
 * a computation-spec), read/explain (payload in {@code data}), modify (re-express, diff in {@code
 * changed}), remove. Mutations write in place; reads carry their payload in {@code data}.
 */
class ComputationVerbTest {

    private static final ObjectMapper M = new ObjectMapper();
    private static final String COMP = CliTestSupport.COMPUTATION_FULL_NAME;

    @Test
    void addPersistsAComputation(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.modelWithRule(dir);
        Path spec = dir.resolve("comp.json");
        Files.writeString(spec, """
                { "computedField": "/Subscription/Billing/EffectiveFee", "name": "EffectiveFeeComp",
                  "alternatives": [ {"operation": "[BaseFee] * 2"} ],
                  "messages": [ {"locale":"en_US","text":"twice base"}, {"locale":"de_DE","text":"zweimal Basis"} ] }""");
        var res = CliTestSupport.run("-m", model.toString(), "computation", "add", spec.toString());
        assertEquals(0, res.code(), res.err());
        JsonNode j = M.readTree(res.out());
        assertEquals("applied", j.get("outcome").asText());
        assertTrue(j.get("written").asBoolean());
        assertTrue(j.path("changed").path("computation").asText().contains("EffectiveFeeComp"));
        assertTrue(Files.readString(model).contains("EffectiveFeeComp"), "the computation is persisted");
    }

    @Test
    void addRejectsAnUnknownKeyInTheSpec(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.modelWithRule(dir);
        Path spec = dir.resolve("typo.json");
        // an otherwise-valid computation with a typo'd key (`alternatves`) — rejected, not silently dropped
        Files.writeString(spec, """
                { "computedField": "/Subscription/Billing/EffectiveFee", "name": "TypoComp",
                  "alternatves": [ {"operation": "[BaseFee] * 2"} ],
                  "messages": [ {"locale":"en_US","text":"x"}, {"locale":"de_DE","text":"x"} ] }""");
        var res = CliTestSupport.run("-m", model.toString(), "computation", "add", spec.toString());
        assertEquals(1, res.code(), () -> "an unknown/typo'd key is rejected: " + res.out());
        JsonNode j = M.readTree(res.out());
        assertEquals("rejected", j.get("outcome").asText());
        boolean unknownArg = false;
        for (JsonNode d : j.path("diagnostics")) {
            unknownArg |= "RK_UNKNOWN_ARG".equals(d.path("code").asText());
        }
        assertTrue(unknownArg, () -> "carries RK_UNKNOWN_ARG: " + res.out());
        assertFalse(Files.readString(model).contains("TypoComp"), "nothing persisted on reject");
    }

    @Test
    void addAttachesACommentKeyToTheOperation(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.modelWithRule(dir);
        Path spec = dir.resolve("comp.json");
        Files.writeString(spec, """
                { "computedField": "/Subscription/Billing/EffectiveFee", "name": "EffectiveFeeComp",
                  "comment": "effective fee is twice the base fee",
                  "alternatives": [ {"operation": "[BaseFee] * 2"} ],
                  "messages": [ {"locale":"en_US","text":"twice base"}, {"locale":"de_DE","text":"zweimal Basis"} ] }""");
        var res = CliTestSupport.run("-m", model.toString(), "computation", "add", spec.toString());
        assertEquals(0, res.code(), res.err());
        assertTrue(Files.readString(model).contains(";; effective fee is twice the base fee"),
                "the comment key lands as a leading ;; line on the computation's first operation");
    }

    @Test
    void readReturnsTheComputationInData(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.modelWithRuleAndComputation(dir);
        var res = CliTestSupport.run("-m", model.toString(), "computation", "read", COMP);
        assertEquals(0, res.code(), res.err());
        JsonNode j = M.readTree(res.out());
        assertEquals("read", j.get("outcome").asText());
        assertTrue(j.path("data").get("converted").asBoolean());
        assertTrue(j.path("data").has("calculatedField"));
    }

    @Test
    void explainReturnsTheComputationExplanationInData(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.modelWithRuleAndComputation(dir);
        var res = CliTestSupport.run("-m", model.toString(), "computation", "explain", COMP);
        assertEquals(0, res.code(), res.err());
        JsonNode data = M.readTree(res.out()).get("data");
        assertEquals("/Subscription/Billing/EffectiveFee", data.get("computedField").asText(),
                () -> "carries the ComputationExplanation in `data`: " + res.out());
        assertTrue(data.has("alternatives"), () -> "with the computation's alternatives: " + res.out());
    }

    @Test
    void modifyReExpressesAComputation(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.modelWithRuleAndComputation(dir);
        Path spec = dir.resolve("comp.json");
        Files.writeString(spec, """
                { "computedField": "/Subscription/Billing/EffectiveFee", "name": "EffectiveFeeComp",
                  "alternatives": [ {"operation": "[BaseFee] * 3"} ],
                  "messages": [ {"locale":"en_US","text":"thrice base"}, {"locale":"de_DE","text":"dreimal Basis"} ] }""");
        var res = CliTestSupport.run("-m", model.toString(), "computation", "modify", COMP, "--spec", spec.toString());
        assertEquals(0, res.code(), res.err());
        JsonNode j = M.readTree(res.out());
        assertEquals("applied", j.get("outcome").asText());
        assertTrue(j.get("written").asBoolean());
        assertTrue(j.has("changed"), "carries the rendered diff");
    }

    @Test
    void overlappingPreconditionsRideTheAppliedEnvelopeAsAWarning(@TempDir Path dir) throws Exception {
        // two alternatives with an identical precondition: the kernel accepts (it takes the first match in
        // declaration order), but G5 warns that the second can never win — a WARNING alongside APPLIED.
        Path model = CliTestSupport.modelWithRule(dir);
        Path spec = dir.resolve("comp.json");
        Files.writeString(spec, """
                { "computedField": "/Subscription/Billing/EffectiveFee", "name": "EffectiveFeeComp",
                  "alternatives": [ {"precondition": "[BaseFee] > 0", "operation": "[BaseFee] * 2"},
                                    {"precondition": "[BaseFee] > 0", "operation": "[BaseFee] * 3"} ],
                  "messages": [ {"locale":"en_US","text":"fee"}, {"locale":"de_DE","text":"Gebuehr"} ] }""");
        var res = CliTestSupport.run("-m", model.toString(), "computation", "add", spec.toString());
        assertEquals(0, res.code(), res.err());
        JsonNode j = M.readTree(res.out());
        assertEquals("applied", j.get("outcome").asText(), res::out);
        assertTrue(j.path("diagnostics").toString().contains("RK_COMPUTATION_OVERLAPPING_PRECONDITIONS"),
                () -> "the overlap lint rides the applied envelope: " + res.out());
    }

    @Test
    void aForbiddenConstructInAPreconditionIsLintedAlongsideTheKernelRejection(@TempDir Path dir) throws Exception {
        // CustomCondition is forbidden in a computation — the kernel rejects, and G5 adds the corrective RK note.
        Path model = CliTestSupport.modelWithRule(dir);
        Path spec = dir.resolve("comp.json");
        Files.writeString(spec, """
                { "computedField": "/Subscription/Billing/EffectiveFee", "name": "EffectiveFeeComp",
                  "alternatives": [ {"precondition": "CustomCondition(SpecialCase)", "operation": "[BaseFee] * 2"} ],
                  "messages": [ {"locale":"en_US","text":"fee"}, {"locale":"de_DE","text":"Gebuehr"} ] }""");
        var res = CliTestSupport.run("-m", model.toString(), "computation", "add", spec.toString());
        assertEquals(1, res.code(), () -> "a forbidden construct is a kernel rejection: " + res.out());
        JsonNode j = M.readTree(res.out());
        assertEquals("rejected", j.get("outcome").asText());
        assertTrue(j.path("diagnostics").toString().contains("RK_COMPUTATION_FORBIDDEN_CONSTRUCT"),
                () -> "the forbidden-construct lint accompanies the kernel rejection: " + res.out());
    }

    @Test
    void removeDeletesAComputation(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.modelWithRuleAndComputation(dir);
        var res = CliTestSupport.run("-m", model.toString(), "computation", "remove", COMP);
        assertEquals(0, res.code(), res.err());
        JsonNode j = M.readTree(res.out());
        assertEquals("applied", j.get("outcome").asText());
        assertEquals(COMP, j.path("changed").path("removed").asText());
        assertFalse(Files.readString(model).contains("EffectiveFeeComp"));
    }
}
