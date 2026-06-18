package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The {@code compute-doc} verb — run the real kernel's runtime computation over a document INSTANCE and
 * report what each computed field becomes (value / cleared / error). Read-only: it prints outcomes, never
 * writes the document. Uses the subscription fixture with {@code EffectiveFeeComp = [BaseFee]}.
 */
class ComputeDocVerbTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode computedFor(String out, String fieldPath) throws Exception {
        for (JsonNode c : MAPPER.readTree(out).get("data").get("computed")) {
            if (c.get("field").asText().equals(fieldPath)) {
                return c;
            }
        }
        return null;
    }

    @Test
    void computesTheValueFromItsOperand(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.modelWithRuleAndComputation(dir);
        Path doc = dir.resolve("doc.json");
        Files.writeString(doc, """
                { "fields": { "Subscription": { "Billing": { "BaseFee": "50" } } } }""");
        var res = CliTestSupport.run("-m", model.toString(), "model", "compute", "--doc", doc.toString());
        assertEquals(0, res.code(), res::err);
        JsonNode cw = computedFor(res.out(), "/Subscription/Billing/EffectiveFee");
        assertTrue(cw != null && cw.get("outcome").asText().equals("value") && cw.get("value").asText().equals("50"),
                () -> "EffectiveFee computes to BaseFee (50): " + res.out());
    }

    @Test
    void anEmptyNumberOperandComputesZero(@TempDir Path dir) throws Exception {
        // KERNEL FACT (type-specific): in a COMPUTATION, an empty NUMBER operand is substituted with 0
        // (so EffectiveFee = [BaseFee] computes "0" when BaseFee is absent) — unlike an empty DATE operand,
        // which computes NO value (DateRangeComputationLawsTest). The empty→0 rule extends to computation.
        Path model = CliTestSupport.modelWithRuleAndComputation(dir);
        Path doc = dir.resolve("doc.json");
        Files.writeString(doc, """
                { "fields": { "Subscription": { "Billing": { } } } }""");
        var res = CliTestSupport.run("-m", model.toString(), "model", "compute", "--doc", doc.toString());
        assertEquals(0, res.code(), res::err);
        JsonNode cw = computedFor(res.out(), "/Subscription/Billing/EffectiveFee");
        assertTrue(cw != null && cw.get("outcome").asText().equals("value") && cw.get("value").asText().equals("0"),
                () -> "an empty number operand is substituted with 0 in a computation: " + res.out());
    }

    @Test
    void aGuardedNumberComputationClearsWhereUnguardedComputesZero(@TempDir Path dir) throws Exception {
        // The premise behind the computation judge's TYPE-DEPENDENT empty-operand rule: for a NUMBER operand
        // a presence guard is NOT redundant. Unguarded EffectiveFee=[BaseFee] computes 0 on an empty BaseFee
        // (the other test); the SAME computation guarded by FieldFilled(BaseFee) computes NOTHING (cleared) —
        // so guarded != unguarded for numbers (unlike a DATE operand, where the guard is redundant).
        Path base = CliTestSupport.modelWithRule(dir);
        Path spec = dir.resolve("guarded.json");
        Files.writeString(spec, """
                { "computedField": "/Subscription/Billing/EffectiveFee", "commonPrecondition": "FieldFilled(BaseFee)",
                  "alternatives": [ { "operation": "[BaseFee]" } ],
                  "messages": [ {"locale":"en_US","text":"x"}, {"locale":"de_DE","text":"y"} ] }""");
        Path guarded = dir.resolve("guarded.dm.json");
        var added = CliTestSupport.run("-m", base.toString(), "computation", "add",
                spec.toString(), "-o", guarded.toString());
        assertEquals(0, added.code(), added::err);

        Path doc = dir.resolve("empty.json");
        Files.writeString(doc, """
                { "fields": { "Subscription": { "Billing": { } } } }""");
        var res = CliTestSupport.run("-m", guarded.toString(), "model", "compute", "--doc", doc.toString());
        assertEquals(0, res.code(), res::err);
        assertNull(computedFor(res.out(), "/Subscription/Billing/EffectiveFee"),
                () -> "a guarded number computation computes nothing on an empty operand: " + res.out());
    }

    @Test
    void unknownPathInTheDocumentFails(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.modelWithRuleAndComputation(dir);
        Path doc = dir.resolve("doc.json");
        Files.writeString(doc, """
                { "fields": { "Subscription": { "Nope": "x" } } }""");
        var res = CliTestSupport.run("-m", model.toString(), "model", "compute", "--doc", doc.toString());
        assertNotEquals(0, res.code(), "an unknown path is an error");
        assertTrue(res.err().contains("Nope"), () -> "names the bad path: " + res.err());
    }
}
