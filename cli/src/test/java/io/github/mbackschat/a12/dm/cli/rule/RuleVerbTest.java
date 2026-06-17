package io.github.mbackschat.a12.dm.cli.rule;

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
 * The target-first {@code rule} group (CLI-SPEC §5). {@code rule check} validates a candidate against the
 * real kernel (no persist) and reports through the result envelope: a read op, so the candidate's validity
 * rides {@code valid} (distinct from {@code ok} = the check executed), with the kernel + lint findings in
 * {@code diagnostics}.
 */
class RuleVerbTest {

    private static final ObjectMapper M = new ObjectMapper();

    @Test
    void checkReportsAValidCandidate(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.modelWithRule(dir);
        var res = CliTestSupport.run("-m", model.toString(), "rule", "check",
                "--field", "/Subscription/Billing/EffectiveFee",
                "--condition", "[EffectiveFee] < [BaseFee]",
                "--code", "EFFECTIVE_FEE_BELOW_BASE");
        assertEquals(0, res.code(), res.err());
        JsonNode j = M.readTree(res.out());
        assertEquals("rule", j.get("target").asText());
        assertEquals("check", j.get("op").asText());
        assertEquals("read", j.get("outcome").asText());
        assertTrue(j.get("ok").asBoolean(), "the check op executed");
        assertTrue(j.get("valid").asBoolean(), "the candidate is kernel-valid");
        assertTrue(j.path("diagnostics").isArray());
    }

    @Test
    void checkReportsAnInvalidCandidateWithDiagnostics(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.modelWithRule(dir);
        var res = CliTestSupport.run("-m", model.toString(), "rule", "check",
                "--field", "/Subscription/Billing/EffectiveFee",
                "--condition", "[EffectiveFee] < [Nonexistent]",
                "--code", "X");
        assertEquals(1, res.code());
        JsonNode j = M.readTree(res.out());
        assertTrue(j.get("ok").asBoolean(), "the check op itself executed");
        assertFalse(j.get("valid").asBoolean(), "the candidate is not kernel-valid");
        assertTrue(j.path("diagnostics").size() > 0, "carries the kernel diagnostics");
    }

    @Test
    void readReturnsARulesConditionInData(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.modelWithRule(dir);
        var res = CliTestSupport.run("-m", model.toString(), "rule", "read", CliTestSupport.RULE_FULL_NAME);
        assertEquals(0, res.code(), res.err());
        JsonNode j = M.readTree(res.out());
        assertEquals("rule", j.get("target").asText());
        assertEquals("read", j.get("op").asText());
        assertEquals("read", j.get("outcome").asText());
        assertTrue(j.get("ok").asBoolean());
        JsonNode data = j.get("data");
        assertEquals(CliTestSupport.RULE_FULL_NAME, data.get("rule").asText());
        assertTrue(data.get("converted").asBoolean(), "FeeCapRule reads to Converted");
        assertTrue(data.has("condition"), "carries the rendered condition");
    }

    @Test
    void explainReturnsTheRuleExplanationInData(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.modelWithRule(dir);
        JsonNode legacy = M.readTree(
                CliTestSupport.run("explain", model.toString(), "--rule", CliTestSupport.RULE_FULL_NAME).out());
        var res = CliTestSupport.run("-m", model.toString(), "rule", "explain", CliTestSupport.RULE_FULL_NAME);
        assertEquals(0, res.code(), res.err());
        JsonNode j = M.readTree(res.out());
        assertEquals("rule", j.get("target").asText());
        assertEquals("explain", j.get("op").asText());
        assertEquals("read", j.get("outcome").asText());
        assertEquals(legacy, j.get("data"), "rule explain carries the RuleExplanation in `data`");
    }

    @Test
    void addPersistsAValidRuleAndReportsApplied(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.modelWithRule(dir);
        Path spec = dir.resolve("rule.json");
        Files.writeString(spec, """
                { "field": "/Subscription/Billing/EffectiveFee", "condition": "[EffectiveFee] < [BaseFee]",
                  "code": "EFFECTIVE_FEE_BELOW_BASE",
                  "messages": [ {"locale":"en_US","text":"Effective fee below base."},
                                {"locale":"de_DE","text":"Effektivgebuehr unter Basis."} ] }""");
        var res = CliTestSupport.run("-m", model.toString(), "rule", "add", spec.toString());
        assertEquals(0, res.code(), res.err());
        JsonNode j = M.readTree(res.out());
        assertEquals("rule", j.get("target").asText());
        assertEquals("add", j.get("op").asText());
        assertEquals("applied", j.get("outcome").asText());
        assertTrue(j.get("written").asBoolean());
        assertTrue(j.path("changed").path("rule").asText().contains("EFFECTIVE_FEE_BELOW_BASE"));
        assertTrue(Files.readString(model).contains("EFFECTIVE_FEE_BELOW_BASE"), "the rule is persisted");
    }

    @Test
    void addRejectsAnInvalidRuleAndWritesNothing(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.modelWithRule(dir);
        Path spec = dir.resolve("bad.json");
        Files.writeString(spec, """
                { "field": "/Subscription/Billing/EffectiveFee", "condition": "[EffectiveFee] < [Nonexistent]",
                  "code": "BAD_RULE",
                  "messages": [ {"locale":"en_US","text":"x"}, {"locale":"de_DE","text":"x"} ] }""");
        var res = CliTestSupport.run("-m", model.toString(), "rule", "add", spec.toString());
        assertEquals(1, res.code());
        JsonNode j = M.readTree(res.out());
        assertEquals("rejected", j.get("outcome").asText());
        assertFalse(j.get("written").asBoolean());
        assertTrue(j.path("diagnostics").size() > 0);
        assertFalse(Files.readString(model).contains("BAD_RULE"), "nothing persisted on reject");
    }

    @Test
    void removeDeletesARuleAndReportsApplied(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.modelWithRule(dir);
        var res = CliTestSupport.run("-m", model.toString(), "rule", "remove", CliTestSupport.RULE_FULL_NAME);
        assertEquals(0, res.code(), res.err());
        JsonNode j = M.readTree(res.out());
        assertEquals("remove", j.get("op").asText());
        assertEquals("applied", j.get("outcome").asText());
        assertTrue(j.get("written").asBoolean());
        assertEquals(CliTestSupport.RULE_FULL_NAME, j.path("changed").path("removed").asText());
        assertFalse(Files.readString(model).contains("FeeCapRule"), "the rule is gone");
    }

    @Test
    void modifyReExpressesARuleConditionAndReportsApplied(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.modelWithRule(dir);
        var res = CliTestSupport.run("-m", model.toString(), "rule", "modify", CliTestSupport.RULE_FULL_NAME,
                "--condition", "[MonthlyFee] > 2000");
        assertEquals(0, res.code(), res.err());
        JsonNode j = M.readTree(res.out());
        assertEquals("modify", j.get("op").asText());
        assertEquals("applied", j.get("outcome").asText());
        assertTrue(j.get("written").asBoolean());
        assertTrue(j.has("changed"), "carries the rendered before/after diff");
        assertTrue(Files.readString(model).contains("2000"), "the new condition is persisted");
    }

    @Test
    void modifySetsSeverityAndMessagesWithoutACondition(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.modelWithRule(dir);   // FeeCapRule, ERROR; model declares en_US + de_DE
        var res = CliTestSupport.run("-m", model.toString(), "rule", "modify", CliTestSupport.RULE_FULL_NAME,
                "--severity", "WARNING",
                "--message", "en_US=Fee is high.", "--message", "de_DE=Gebühr ist hoch.");
        assertEquals(0, res.code(), res.err());
        JsonNode j = M.readTree(res.out());
        assertEquals("applied", j.get("outcome").asText());
        assertEquals("WARNING", j.path("changed").path("severity").asText());
        String written = Files.readString(model);
        assertTrue(written.contains("WARNING"), "the new severity is persisted");
        assertTrue(written.contains("Fee is high."), "the new message is persisted");
    }

    @Test
    void modifyRejectsAnInvalidReExpression(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.modelWithRule(dir);
        var res = CliTestSupport.run("-m", model.toString(), "rule", "modify", CliTestSupport.RULE_FULL_NAME,
                "--condition", "[MonthlyFee] > [Nonexistent]");
        assertEquals(1, res.code());
        assertEquals("rejected", M.readTree(res.out()).get("outcome").asText());
    }
}
