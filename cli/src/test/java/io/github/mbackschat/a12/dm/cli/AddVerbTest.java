package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;

/**
 * The {@code add} verb — persist a rule from a JSON rule-spec, completing the BA loop
 * (check → add → saved model). The condition is kernel-validated before persisting; the persisted
 * model is re-validated (catching message/locale coverage the condition-check masks).
 */
class AddVerbTest {

    private static Path model(Path dir) throws IOException {
        Path f = dir.resolve("m.dm.json");
        Files.writeString(f, CliTestSupport.subscriptionJson());
        return f;
    }

    private static Path spec(Path dir, String json) throws IOException {
        Path f = dir.resolve("spec.json");
        Files.writeString(f, json);
        return f;
    }

    /** The subscription model declares en_US + de_DE, so a valid rule needs both messages. */
    private static final String GOOD_SPEC = """
            {
              "field": "/Subscription/Billing/EffectiveFee",
              "condition": "[EffectiveFee] < [BaseFee]",
              "code": "EFFECTIVE_FEE_BELOW_BASE",
              "name": "EffectiveFeeNotBelowBase",
              "messages": [
                {"locale": "en_US", "text": "Effective fee must not be below the base fee."},
                {"locale": "de_DE", "text": "Effektives Entgelt darf nicht unter dem Grundentgelt liegen."}
              ]
            }
            """;

    @Test
    void validSpecPersistsTheRule(@TempDir Path dir) throws Exception {
        Path m = model(dir);
        var res = CliTestSupport.run("-m", m.toString(), "rule", "add", spec(dir, GOOD_SPEC).toString());
        assertEquals(0, res.code(), res::err);
        // the rule is written back into the model file (in place by default)
        String newModel = Files.readString(m);
        assertTrue(newModel.contains("EffectiveFeeNotBelowBase"), "the new model carries the rule");
        assertTrue(newModel.contains("EFFECTIVE_FEE_BELOW_BASE"), "with its code");
        assertTrue(newModel.contains("must not be below the base fee"), "and its real message");
        // it is a valid, reloadable model that now contains the rule
        BoundModel reloaded = BoundModel.ofJson(newModel);
        assertTrue(reloaded.toJson().contains("EffectiveFeeNotBelowBase"), "round-trips through the kernel");
    }

    @Test
    void commentKeyPersistsALeadingCommentOnTheCondition(@TempDir Path dir) throws Exception {
        String withComment = """
                {
                  "field": "/Subscription/Billing/EffectiveFee",
                  "condition": "[EffectiveFee] < [BaseFee]",
                  "comment": "effective fee must never dip below the base fee",
                  "code": "EFFECTIVE_FEE_BELOW_BASE",
                  "name": "EffectiveFeeNotBelowBase",
                  "messages": [
                    {"locale": "en_US", "text": "Effective fee must not be below the base fee."},
                    {"locale": "de_DE", "text": "Effektives Entgelt darf nicht unter dem Grundentgelt liegen."}
                  ]
                }
                """;
        Path m = model(dir);
        var res = CliTestSupport.run("-m", m.toString(), "rule", "add", spec(dir, withComment).toString());
        assertEquals(0, res.code(), res::err);
        String newModel = Files.readString(m);
        assertTrue(newModel.contains(";; effective fee must never dip below the base fee"),
                () -> "the comment key lands as a leading ;; line on the persisted condition: " + newModel);
        // still a valid, kernel-reloadable model (the kernel tolerates the comment)
        assertTrue(BoundModel.ofJson(newModel).toJson().contains("EffectiveFeeNotBelowBase"),
                "the commented rule round-trips through the kernel");
    }

    @Test
    void invalidConditionIsNotPersisted(@TempDir Path dir) throws Exception {
        String bad = GOOD_SPEC.replace("[EffectiveFee] < [BaseFee]", "@@@ not a condition @@@");
        Path m = model(dir);
        String before = Files.readString(m);
        var res = CliTestSupport.run("-m", m.toString(), "rule", "add", spec(dir, bad).toString());
        assertEquals(1, res.code(), "a kernel-rejected condition is not persisted");
        assertTrue(res.out().contains("\"outcome\" : \"rejected\""),
                () -> "the envelope reports the rejection: " + res.out());
        assertEquals(before, Files.readString(m), "and nothing is written to the model file");
    }

    @Test
    void missingMessageForADeclaredLocaleIsRejected(@TempDir Path dir) throws Exception {
        // only en_US — the subscription model also declares de_DE, so the persisted model is invalid
        String oneLocale = """
                {"field":"/Subscription/Billing/EffectiveFee","condition":"[EffectiveFee] < [BaseFee]",
                 "code":"X","name":"R","messages":[{"locale":"en_US","text":"x"}]}
                """;
        var res = CliTestSupport.run("-m", model(dir).toString(), "rule", "add", spec(dir, oneLocale).toString());
        assertNotEquals(0, res.code(), "missing a declared-locale message must fail");
    }

    @Test
    void warnsOnInvertedRequirednessButStillPersists(@TempDir Path dir) throws Exception {
        // the requirement written positively (FieldFilled on the error field): kernel-valid, but it's
        // the inverted polarity — fires when the fee IS present. add persists it (it's valid) and the
        // lint backstop rides the applied envelope's diagnostics as RK_INVERTED_REQUIREDNESS.
        String inverted = """
                {"field":"/Subscription/Addons/MonthlyFee","condition":"FieldFilled(MonthlyFee)",
                 "code":"FEE_PRESENT","name":"FeePresent",
                 "messages":[{"locale":"en_US","text":"x"},{"locale":"de_DE","text":"y"}]}
                """;
        Path m = model(dir);
        var res = CliTestSupport.run("-m", m.toString(), "rule", "add", spec(dir, inverted).toString());
        assertEquals(0, res.code(), res::err);
        assertTrue(Files.readString(m).contains("FeePresent"), "the (valid) rule is still persisted");
        assertTrue(res.out().contains("RK_INVERTED_REQUIREDNESS"),
                () -> "the lint backstop warns about the inverted polarity: " + res.out());
    }

    @Test
    void missingMessagesAltogetherFails(@TempDir Path dir) throws Exception {
        String noMsg = """
                {"field":"/Subscription/Billing/EffectiveFee","condition":"[EffectiveFee] < [BaseFee]","code":"X"}
                """;
        var res = CliTestSupport.run("-m", model(dir).toString(), "rule", "add", spec(dir, noMsg).toString());
        assertNotEquals(0, res.code(), "a rule-spec without messages is an error");
        assertTrue(res.err().toLowerCase().contains("message"), () -> "names the gap: " + res.err());
    }
}
