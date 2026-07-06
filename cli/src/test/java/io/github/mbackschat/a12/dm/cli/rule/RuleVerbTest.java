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
    void readWithNoPathListsTheRuleNames(@TempDir Path dir) throws Exception {
        // P2: no path → a lean name-index (the navigation handle), uniform with include/typedef read.
        Path model = CliTestSupport.modelWithRule(dir);
        var res = CliTestSupport.run("-m", model.toString(), "rule", "read");
        assertEquals(0, res.code(), res.err());
        JsonNode j = M.readTree(res.out());
        assertEquals("read", j.get("outcome").asText());
        assertTrue(j.get("ok").asBoolean());
        JsonNode rules = j.path("data").path("rules");
        assertTrue(rules.isArray() && rules.size() > 0, () -> "lists rule full-names: " + res.out());
        assertTrue(rules.toString().contains(CliTestSupport.RULE_FULL_NAME), () -> "includes the known rule: " + res.out());
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
        // slot parity vs IRule (probe 11 + the slot sweep): a rule read must surface the severity, the
        // error code, and the error field — not only the condition + messages — so each is confirmable
        // through the structured surface (you can't confirm a severity/code you set otherwise).
        assertTrue(data.path("severity").asText().matches("ERROR|WARNING|INFO"),
                () -> "carries severity (ERROR/WARNING/INFO): " + data);
        assertFalse(data.path("errorCode").asText().isBlank(), () -> "carries the error code: " + data);
        assertFalse(data.path("errorField").asText().isBlank(), () -> "carries the error field: " + data);
    }

    @Test
    void explainReturnsTheRuleExplanationInData(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.modelWithRule(dir);
        var res = CliTestSupport.run("-m", model.toString(), "rule", "explain", CliTestSupport.RULE_FULL_NAME);
        assertEquals(0, res.code(), res.err());
        JsonNode j = M.readTree(res.out());
        assertEquals("rule", j.get("target").asText());
        assertEquals("explain", j.get("op").asText());
        assertEquals("read", j.get("outcome").asText());

        JsonNode data = j.get("data");
        assertTrue(data.has("operators") && data.has("references"),
                () -> "rule explain carries the RuleExplanation in `data`: " + res.out());
        // polarity — so an agent explaining the rule to a user doesn't invert its meaning (the A12 trap)
        assertEquals("violation", data.path("polarity").path("firesOn").asText(),
                () -> "explain states the rule fires on a VIOLATION: " + res.out());
        assertFalse(data.path("polarity").path("note").asText().isBlank(), "polarity carries a human note");
        // the rule's stored message — the modeler's own NL, the best explanation seed (G10, now on explain too)
        assertEquals("Too high.", data.path("messages").get(0).path("text").asText(),
                () -> "explain surfaces the rule's stored message: " + res.out());
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
    void addPersistsARepetitionNotUniqueRuleViaTheGroupKey(@TempDir Path dir) throws Exception {
        // F06-3: RepetitionNotUnique needs the rule at the PARENT of the repeated group with the error field
        // INSIDE it. `rule check` has --group; `rule add` must take the same `group` key, else a checked rule
        // can't be persisted (the default grouping puts the rule at the repeated group itself → rejected).
        Path model = CliTestSupport.orderRuled(dir);   // has a repeatable /Order/Items with Sku
        Path spec = dir.resolve("dup.json");
        Files.writeString(spec, """
                { "field": "/Order/Items/Sku", "group": "/Order",
                  "condition": "RepetitionNotUnique(Items/Sku)", "code": "DUPLICATE_SKU",
                  "messages": [ {"locale":"en_US","text":"Duplicate SKU."} ] }""");
        var res = CliTestSupport.run("-m", model.toString(), "rule", "add", spec.toString());
        assertEquals(0, res.code(), res.err());
        assertEquals("applied", M.readTree(res.out()).get("outcome").asText());
        assertTrue(Files.readString(model).contains("DUPLICATE_SKU"),
                "the RepetitionNotUnique rule persisted via the group override (check→add round-trip)");
    }

    @Test
    void addPersistsARuleWhoseMessageInterpolatesValueAndRowIndexTokens(@TempDir Path dir) throws Exception {
        // probe 11 (native-only crash): a message with an error-text PARAMETER token ($<Field>.value$,
        // $#<Group>$) invokes the kernel's error-text parameter parser (RuleErrortextChecker), whose
        // ErrortextParameterMultiLanguageHandler.<clinit> loads a LexerTerminals ResourceBundle. No other
        // :cli test exercised this path, so the test-suite-driven native CAPTURE never registered that
        // bundle and ANY token message crashed the native binary (ExceptionInInitializerError / exit 70 on
        // rule add/modify, even --dry-run). This test drives the capture to register the bundle (the fix)
        // and locks that a token message is accepted + persisted. On the JVM the bundle is on the classpath,
        // so this always passed there — the gap was native-only, which is exactly why a JVM guard alone
        // can't catch it; the native-gate battery check is the trip-wire (see scripts/native-gate.sh).
        Path model = CliTestSupport.orderRuled(dir);   // repeatable /Order/Items with Count
        Path spec = dir.resolve("tokenmsg.json");
        Files.writeString(spec, """
                { "field": "/Order/Items/Count", "group": "/Order",
                  "condition": "GroupFilled(Items) And [Count] <= 0", "code": "ITEM_COUNT_NOT_POSITIVE",
                  "messages": [ {"locale":"en_US",
                    "text":"Row $#/Order/Items$: count $/Order/Items/Count.value$ must be greater than zero."} ] }""");

        var res = CliTestSupport.run("-m", model.toString(), "rule", "add", spec.toString());
        assertEquals(0, res.code(), res.err());
        assertEquals("applied", M.readTree(res.out()).get("outcome").asText());
        assertTrue(Files.readString(model).contains("ITEM_COUNT_NOT_POSITIVE"),
                "the token-bearing rule persisted (the error-text parameter parser ran — the native capture path)");
    }

    @Test
    void addRejectsAFieldOutsideTheGivenGroup(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);
        Path spec = dir.resolve("bad.json");
        Files.writeString(spec, """
                { "field": "/Order/Items/Sku", "group": "/Order/Nope",
                  "condition": "FieldNotFilled(Sku)", "code": "X",
                  "messages": [ {"locale":"en_US","text":"x"} ] }""");
        var res = CliTestSupport.run("-m", model.toString(), "rule", "add", spec.toString());
        assertTrue(res.code() != 0, "a field outside the given group must be rejected");
        assertTrue((res.out() + res.err()).contains("must be inside the rule group"),
                () -> "names the group mismatch: " + res.out() + res.err());
    }

    @Test
    void addPersistsAnInfoSeverityRule(@TempDir Path dir) throws Exception {
        // The self-describing surface (add.schema severity description, rule check --severity paramLabel,
        // DraftRule javadoc) advertises INFO alongside ERROR|WARNING; this locks that INFO is real —
        // kernel-accepted and persisted — not just documented.
        Path model = CliTestSupport.modelWithRule(dir);
        Path spec = dir.resolve("info-rule.json");
        Files.writeString(spec, """
                { "field": "/Subscription/Billing/EffectiveFee", "condition": "[EffectiveFee] < [BaseFee]",
                  "code": "EFFECTIVE_FEE_BELOW_BASE", "severity": "INFO",
                  "messages": [ {"locale":"en_US","text":"Effective fee below base."},
                                {"locale":"de_DE","text":"Effektivgebuehr unter Basis."} ] }""");
        var res = CliTestSupport.run("-m", model.toString(), "rule", "add", spec.toString());
        assertEquals(0, res.code(), res.err());
        assertEquals("applied", M.readTree(res.out()).get("outcome").asText());
        assertTrue(Files.readString(model).contains("INFO"), "the INFO severity is persisted");
    }

    @Test
    void addWithAllowDifferingDecimalsAcceptsACrossScaleComparison(@TempDir Path dir) throws Exception {
        // /Order/Quantity (scale 0) == /Order/UnitPrice (scale 2) draws MVK_INVALID_COMPARE_DEC_PLACES;
        // the "Allow Differing Decimal Places" flag (G7b) prepends @SuppressWarning to waive it.
        Path model = CliTestSupport.orderRuled(dir);
        Path bare = dir.resolve("bare.json");
        Files.writeString(bare, """
                { "field": "/Order/Quantity", "condition": "[Quantity] == [UnitPrice]", "code": "SCALE_BARE",
                  "messages": [ {"locale":"en_US","text":"x"} ] }""");
        assertEquals(1, CliTestSupport.run("-m", model.toString(), "rule", "add", bare.toString()).code(),
                "without the flag the cross-scale comparison is rejected");

        Path ok = dir.resolve("ok.json");
        Files.writeString(ok, """
                { "field": "/Order/Quantity", "condition": "[Quantity] == [UnitPrice]", "code": "SCALE_OK",
                  "allowDifferingDecimals": true,
                  "messages": [ {"locale":"en_US","text":"x"} ] }""");
        var res = CliTestSupport.run("-m", model.toString(), "rule", "add", ok.toString());
        assertEquals(0, res.code(), res::err);
        assertEquals("applied", M.readTree(res.out()).path("outcome").asText());
        assertTrue(Files.readString(model).contains("SuppressWarning"),
                "the @SuppressWarning directive is persisted on the condition");
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
    void addRejectsADuplicateRuleNameWithoutLeakingAStackTrace(@TempDir Path dir) throws Exception {
        // The probe's exact repro: add a rule, then add the SAME code again. The kernel throws
        // IllegalArgumentException ("two child elements with the same name") during conversion; the verb
        // must surface a clean, structured RK_NAME_EXISTS rejection — never a raw Java stack trace.
        Path model = CliTestSupport.modelWithRule(dir);
        Path spec = dir.resolve("dup.json");
        Files.writeString(spec, """
                { "field": "/Subscription/Billing/EffectiveFee", "condition": "[EffectiveFee] < [BaseFee]",
                  "code": "DUP_RULE",
                  "messages": [ {"locale":"en_US","text":"x"}, {"locale":"de_DE","text":"x"} ] }""");
        assertEquals(0, CliTestSupport.run("-m", model.toString(), "rule", "add", spec.toString()).code(),
                "first add succeeds");

        var res = CliTestSupport.run("-m", model.toString(), "rule", "add", spec.toString());
        assertTrue(res.err().isBlank(), () -> "no stack trace / raw exception on stderr: " + res.err());
        JsonNode j = M.readTree(res.out());
        assertEquals("rejected", j.get("outcome").asText());
        assertFalse(j.get("written").asBoolean());
        boolean nameExists = false;
        for (JsonNode d : j.path("diagnostics")) {
            nameExists |= "RK_NAME_EXISTS".equals(d.path("code").asText());
        }
        assertTrue(nameExists, () -> "carries RK_NAME_EXISTS: " + res.out());
    }

    @Test
    void addRejectsAnUnknownKeyInTheSpec(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.modelWithRule(dir);
        Path spec = dir.resolve("typo.json");
        // an otherwise-valid rule with a typo'd optional key (`severty`) — must be rejected, not silently dropped
        Files.writeString(spec, """
                { "field": "/Subscription/Billing/EffectiveFee", "condition": "[EffectiveFee] < [BaseFee]",
                  "code": "TYPO_RULE", "severty": "WARNING",
                  "messages": [ {"locale":"en_US","text":"x"}, {"locale":"de_DE","text":"x"} ] }""");
        var res = CliTestSupport.run("-m", model.toString(), "rule", "add", spec.toString());
        assertEquals(1, res.code(), () -> "an unknown/typo'd key is rejected: " + res.out());
        JsonNode j = M.readTree(res.out());
        assertEquals("rejected", j.get("outcome").asText());
        boolean unknownArg = false;
        for (JsonNode d : j.path("diagnostics")) {
            unknownArg |= "RK_UNKNOWN_ARG".equals(d.path("code").asText());
        }
        assertTrue(unknownArg, () -> "carries RK_UNKNOWN_ARG with a did-you-mean: " + res.out());
        assertFalse(Files.readString(model).contains("TYPO_RULE"), "nothing persisted on reject");
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
    void modifyMergesMessagesRatherThanReplacing(@TempDir Path dir) throws Exception {
        // G17: FeeCapRule has en_US "Too high." + de_DE "Zu hoch.". Updating ONLY en_US must PRESERVE de_DE —
        // under the old replace-only behaviour de_DE was dropped, leaving the model invalid (kernel reject).
        Path model = CliTestSupport.modelWithRule(dir);
        var res = CliTestSupport.run("-m", model.toString(), "rule", "modify", CliTestSupport.RULE_FULL_NAME,
                "--message", "en_US=Way too high.");
        assertEquals(0, res.code(), res::err);
        assertEquals("applied", M.readTree(res.out()).get("outcome").asText());
        var read = CliTestSupport.run("-m", model.toString(), "rule", "read", CliTestSupport.RULE_FULL_NAME);
        String msgs = M.readTree(read.out()).path("data").path("messages").toString();
        assertTrue(msgs.contains("Way too high."), () -> "en_US is updated: " + read.out());
        assertTrue(msgs.contains("Zu hoch."), () -> "de_DE is preserved (merge, not replace): " + read.out());
    }

    @Test
    void modifyRejectsAnInvalidReExpression(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.modelWithRule(dir);
        var res = CliTestSupport.run("-m", model.toString(), "rule", "modify", CliTestSupport.RULE_FULL_NAME,
                "--condition", "[MonthlyFee] > [Nonexistent]");
        assertEquals(1, res.code());
        assertEquals("rejected", M.readTree(res.out()).get("outcome").asText());
    }

    @Test
    void modifySetsAndReadsBackRuleMetadata(@TempDir Path dir) throws Exception {
        // G39 — a rule's annotation + internal/external descriptions (the rule peer of group metadata; rules
        // have no label slot). Metadata-only modify needs no condition/severity/message.
        Path model = CliTestSupport.modelWithRule(dir);
        var res = CliTestSupport.run("-m", model.toString(), "rule", "modify", CliTestSupport.RULE_FULL_NAME,
                "--annotation", "owner=risk-team",
                "--internal-description", "en_US=why this cap exists",
                "--external-description", "en_US=your fee is capped");
        assertEquals(0, res.code(), res::err);
        JsonNode changed = M.readTree(res.out()).path("changed");
        assertEquals("risk-team", changed.path("annotation.owner").asText());
        assertEquals("why this cap exists", changed.path("internalDescription.en_US").asText());
        // round-trips through rule read (the read-side of G39)
        var read = CliTestSupport.run("-m", model.toString(), "rule", "read", CliTestSupport.RULE_FULL_NAME);
        JsonNode data = M.readTree(read.out()).path("data");
        assertEquals("risk-team", data.path("annotations").path("owner").asText(), () -> read.out());
        assertEquals("why this cap exists", data.path("internalDescription").path("en_US").asText());
        assertEquals("your fee is capped", data.path("externalDescription").path("en_US").asText());
    }

    @Test
    void modifyMetadataLeavesTheConditionIntact(@TempDir Path dir) throws Exception {
        // a metadata edit must not disturb the rule's condition/severity (only whitespace may re-serialize)
        Path model = CliTestSupport.modelWithRule(dir);
        String before = M.readTree(CliTestSupport.run("-m", model.toString(), "rule", "read",
                CliTestSupport.RULE_FULL_NAME).out()).path("data").path("condition").asText();
        assertEquals(0, CliTestSupport.run("-m", model.toString(), "rule", "modify", CliTestSupport.RULE_FULL_NAME,
                "--annotation", "owner=risk-team").code());
        JsonNode after = M.readTree(CliTestSupport.run("-m", model.toString(), "rule", "read",
                CliTestSupport.RULE_FULL_NAME).out()).path("data");
        assertEquals(before, after.path("condition").asText(), "the condition is untouched by a metadata edit");
    }

    @Test
    void modifyRemovesARuleAnnotation(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.modelWithRule(dir);
        assertEquals(0, CliTestSupport.run("-m", model.toString(), "rule", "modify", CliTestSupport.RULE_FULL_NAME,
                "--annotation", "owner=risk-team").code());
        var res = CliTestSupport.run("-m", model.toString(), "rule", "modify", CliTestSupport.RULE_FULL_NAME,
                "--remove-annotation", "owner");
        assertEquals(0, res.code(), res::err);
        var read = CliTestSupport.run("-m", model.toString(), "rule", "read", CliTestSupport.RULE_FULL_NAME);
        assertTrue(M.readTree(read.out()).path("data").path("annotations").path("owner").isMissingNode(),
                "the annotation is gone after removal");
    }

    @Test
    void modifyWithNoDirectiveIsAUsageError(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.modelWithRule(dir);
        assertEquals(2, CliTestSupport.run("-m", model.toString(), "rule", "modify", CliTestSupport.RULE_FULL_NAME).code(),
                "no condition/severity/message/metadata is a usage error");
    }
}
