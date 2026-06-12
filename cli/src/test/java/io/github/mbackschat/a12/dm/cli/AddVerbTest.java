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
        var res = CliTestSupport.run("add", model(dir).toString(), spec(dir, GOOD_SPEC).toString());
        assertEquals(0, res.code(), res::err);
        String newModel = res.out();
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
        var res = CliTestSupport.run("add", model(dir).toString(), spec(dir, withComment).toString());
        assertEquals(0, res.code(), res::err);
        assertTrue(res.out().contains(";; effective fee must never dip below the base fee"),
                () -> "the comment key lands as a leading ;; line on the persisted condition: " + res.out());
        // still a valid, kernel-reloadable model (the kernel tolerates the comment)
        assertTrue(BoundModel.ofJson(res.out()).toJson().contains("EffectiveFeeNotBelowBase"),
                "the commented rule round-trips through the kernel");
    }

    @Test
    void invalidConditionIsNotPersisted(@TempDir Path dir) throws Exception {
        String bad = GOOD_SPEC.replace("[EffectiveFee] < [BaseFee]", "@@@ not a condition @@@");
        var res = CliTestSupport.run("add", model(dir).toString(), spec(dir, bad).toString());
        assertEquals(1, res.code(), "a kernel-rejected condition is not persisted");
        assertTrue(res.err().contains("\"persisted\" : false"), () -> "stderr reports the rejection: " + res.err());
        assertTrue(res.out().isBlank(), "and nothing is emitted to stdout");
    }

    @Test
    void missingMessageForADeclaredLocaleIsRejected(@TempDir Path dir) throws Exception {
        // only en_US — the subscription model also declares de_DE, so the persisted model is invalid
        String oneLocale = """
                {"field":"/Subscription/Billing/EffectiveFee","condition":"[EffectiveFee] < [BaseFee]",
                 "code":"X","name":"R","messages":[{"locale":"en_US","text":"x"}]}
                """;
        var res = CliTestSupport.run("add", model(dir).toString(), spec(dir, oneLocale).toString());
        assertNotEquals(0, res.code(), "missing a declared-locale message must fail");
    }

    @Test
    void warnsOnInvertedRequirednessButStillPersists(@TempDir Path dir) throws Exception {
        // the requirement written positively (FieldFilled on the error field): kernel-valid, but it's
        // the inverted polarity — fires when the fee IS present. add persists it (it's valid) and the
        // best-effort lint backstop warns RK_INVERTED_REQUIREDNESS on stderr.
        String inverted = """
                {"field":"/Subscription/Addons/MonthlyFee","condition":"FieldFilled(MonthlyFee)",
                 "code":"FEE_PRESENT","name":"FeePresent",
                 "messages":[{"locale":"en_US","text":"x"},{"locale":"de_DE","text":"y"}]}
                """;
        var res = CliTestSupport.run("add", model(dir).toString(), spec(dir, inverted).toString());
        assertEquals(0, res.code(), res::err);
        assertTrue(res.out().contains("FeePresent"), "the (valid) rule is still persisted");
        assertTrue(res.err().contains("RK_INVERTED_REQUIREDNESS"),
                () -> "the lint backstop warns about the inverted polarity: " + res.err());
    }

    @Test
    void missingMessagesAltogetherFails(@TempDir Path dir) throws Exception {
        String noMsg = """
                {"field":"/Subscription/Billing/EffectiveFee","condition":"[EffectiveFee] < [BaseFee]","code":"X"}
                """;
        var res = CliTestSupport.run("add", model(dir).toString(), spec(dir, noMsg).toString());
        assertNotEquals(0, res.code(), "a rule-spec without messages is an error");
        assertTrue(res.err().toLowerCase().contains("message"), () -> "names the gap: " + res.err());
    }
}
