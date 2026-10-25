package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.ModelDocument;

/**
 * The message-token claims of the rule and computation add schemas that their own {@code examples} cannot carry,
 * executed through the tool (CLI-CONFORMANCE-SPEC "no-rot", CG23). The examples run on the subscription fixture,
 * which keys no group and declares no category, so the keyed tokens run on keyed fixtures, the German spelling on a
 * German-condition one; a refusal is something an example, being required to pass, cannot show. Each row also asserts
 * that the schema still names what it ran, so the description and the behaviour cannot part.
 */
class MessageTokenSchemaClaimsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void aKeyedFieldTokenIsAdmittedBesideTheSameKeyedRead(@TempDir Path dir) throws Exception {
        String text = description("rule-add", "messages");
        assertTrue(text.contains("$<Field>.value For \\\"key\\\"$".replace("\\\"", "\"")), text);
        assertTrue(text.contains("$<Field> For \"key\"$"), text);

        String spec = """
                {"field": "/Policy/PolicyNumber",
                 "condition": "FieldFilled(/Policy/PolicyNumber) And FieldFilled(/Policy/Claims/Amount For \\"c1\\")",
                 "code": "CLAIM_AMOUNT",
                 "messages": [{"locale": "en_US",
                   "text": "Claim $/Policy/Claims/Amount For \\"c1\\"$ is $/Policy/Claims/Amount.value For \\"c1\\"$."}]}
                """;
        CliTestSupport.Result admitted = add(dir, "policy-claims", "rule", spec);
        assertEquals(0, admitted.code(), admitted::toString);

        CliTestSupport.Result otherKey = add(dir, "policy-claims", "rule",
                spec.replace("Amount.value For \\\"c1\\\"", "Amount.value For \\\"c2\\\""));
        assertNotEquals(0, otherKey.code(), otherKey::toString);
        assertTrue(otherKey.out().contains("MVK_INDEX_FOR_ERROR_TEXT_INVALID"), otherKey::toString);
        assertTrue(text.contains("MVK_INDEX_FOR_ERROR_TEXT_INVALID"), text);
    }

    @Test
    void aComputationInARootGroupRefusesTheRuleGroupShorthand(@TempDir Path dir) throws Exception {
        String text = description("computation-add", "messages");
        assertTrue(text.contains("RULEGROUP_IMMEDIATELY_BELOW_ROOT"), text);

        String spec = """
                {"computedField": "/Subscription/Billing/EffectiveFee", "group": "/Subscription",
                 "alternatives": [{"operation": "[/Subscription/Billing/BaseFee]"}],
                 "messages": [{"locale": "en_US", "text": "Subscription $#RuleGroup$."},
                              {"locale": "de_DE", "text": "Abonnement $#RuleGroup$."}]}
                """;
        CliTestSupport.Result refused = add(dir, "subscription", "computation", spec);
        assertNotEquals(0, refused.code(), refused::toString);
        assertTrue(refused.out().contains("RULEGROUP_IMMEDIATELY_BELOW_ROOT"), refused::toString);
    }

    /** A keyed policy-claims model whose conditions are written in {@code conditionLanguage}; own domain. */
    private static String claims(String conditionLanguage) {
        String json = DocumentModelDraft.named("policy-claim-tokens").locale("en_US")
                .rootGroup("Policy", p -> p.stringField("Holder", f -> { })
                        .group("Claims", c -> c.repeatable(5).indexField("Ref")
                                .stringField("Ref", f -> { }).stringField("Note", f -> { })
                                .enumField("Status", e -> e.value("OPEN").value("SHUT").category("For", "A", "B"))))
                .build().toJson();
        return ModelDocument.read(json).setConditionLanguage(conditionLanguage).toJson();
    }

    private static String keyedRule(String condition, String message) {
        return """
                {"field": "/Policy/Holder", "condition": "%s", "code": "CLAIM_NOTE",
                 "messages": [{"locale": "en_US", "text": "%s"}]}
                """.formatted(condition.replace("\"", "\\\""), message.replace("\"", "\\\""));
    }

    @Test
    void aKeywordNamedCategoryIsQuotedInAToken(@TempDir Path dir) throws Exception {
        String text = description("rule-add", "messages");
        assertTrue(text.contains("->'For'"), text);
        String condition = "FieldFilled(/Policy/Holder) And FieldFilled(/Policy/Claims/Status For \"c1\")";
        CliTestSupport.Result quoted = addModel(dir, claims("en_US"), "rule",
                keyedRule(condition, "Claim $/Policy/Claims/Status->'For' For \"c1\"$."));
        assertEquals(0, quoted.code(), quoted::toString);
        CliTestSupport.Result bare = addModel(dir, claims("en_US"), "rule",
                keyedRule(condition, "Claim $/Policy/Claims/Status->For For \"c1\"$."));
        assertNotEquals(0, bare.code(), bare::toString);
        assertTrue(bare.out().contains("MVK_UNEXPECTED_TOKEN"), bare::toString);
    }

    @Test
    void aKeyHoldingADollarCannotBeWrittenInAToken(@TempDir Path dir) throws Exception {
        String text = description("rule-add", "messages");
        assertTrue(text.contains("WRONG_NUMBER_OF_DOLLARS"), text);
        String condition = "FieldFilled(/Policy/Holder) And FieldFilled(/Policy/Claims/Note For \"c$1\")";
        CliTestSupport.Result conditionOnly = addModel(dir, claims("en_US"), "rule", keyedRule(condition, "Check the claim."));
        assertEquals(0, conditionOnly.code(), conditionOnly::toString);
        CliTestSupport.Result token = addModel(dir, claims("en_US"), "rule",
                keyedRule(condition, "Claim $/Policy/Claims/Note.value For \"c$1\"$."));
        assertNotEquals(0, token.code(), token::toString);
        assertTrue(token.out().contains("WRONG_NUMBER_OF_DOLLARS"), token::toString);
    }

    @Test
    void aGermanConditionModelSpellsTheTokenKeywordsInGerman(@TempDir Path dir) throws Exception {
        String text = description("rule-add", "messages");
        assertTrue(text.contains("value as Wert and For as Fuer"), text);
        String condition = "FeldAngegeben(/Policy/Holder) Und FeldAngegeben(/Policy/Claims/Note Fuer \"c1\")";
        CliTestSupport.Result german = addModel(dir, claims("de_DE"), "rule",
                keyedRule(condition, "Claim $/Policy/Claims/Note.Wert Fuer \"c1\"$."));
        assertEquals(0, german.code(), german::toString);
        CliTestSupport.Result english = addModel(dir, claims("de_DE"), "rule",
                keyedRule(condition, "Claim $/Policy/Claims/Note.value For \"c1\"$."));
        assertNotEquals(0, english.code(), english::toString);
    }

    /** The token's field must be read by the computation; a precondition of any alternative counts. */
    @Test
    void aComputationFieldTokenNeedsTheComputationToReadTheField(@TempDir Path dir) throws Exception {
        String text = description("computation-add", "messages");
        assertTrue(text.contains("any alternative reads that field (else INVALID_FIELD)"), text);
        String spec = """
                {"computedField": "/Subscription/Billing/EffectiveFee", "group": "/Subscription/Billing",
                 "alternatives": [{%s"operation": "[/Subscription/Billing/BaseFee]"}],
                 "messages": [{"locale": "en_US", "text": "Fee $/Subscription/Billing/%s.value$."},
                              {"locale": "de_DE", "text": "Gebühr $/Subscription/Billing/%s.value$."}]}
                """;
        String guard = "\"precondition\": \"FieldFilled(/Subscription/Billing/InvoiceEmail)\", ";
        CliTestSupport.Result read = add(dir, "subscription", "computation", spec.formatted("", "BaseFee", "BaseFee"));
        assertEquals(0, read.code(), read::toString);
        CliTestSupport.Result unread = add(dir, "subscription", "computation",
                spec.formatted("", "InvoiceEmail", "InvoiceEmail"));
        assertNotEquals(0, unread.code(), unread::toString);
        assertTrue(unread.out().contains("INVALID_FIELD"), unread::toString);
        CliTestSupport.Result guarded = add(dir, "subscription", "computation",
                spec.formatted(guard, "InvoiceEmail", "InvoiceEmail"));
        assertEquals(0, guarded.code(), guarded::toString);
    }

    private static CliTestSupport.Result addModel(Path dir, String modelJson, String target, String spec)
            throws Exception {
        Path model = Files.createTempFile(dir, "model", ".dm.json");
        Files.writeString(model, modelJson);
        return run(dir, model, target, spec);
    }

    private static CliTestSupport.Result run(Path dir, Path model, String target, String spec) throws Exception {
        Path specFile = Files.createTempFile(dir, target, ".json");
        Files.writeString(specFile, spec);
        return CliTestSupport.run("-m", model.toString(), target, "add", specFile.toString(), "--dry-run");
    }

    private static CliTestSupport.Result add(Path dir, String fixture, String target, String spec) throws Exception {
        Path model = dir.resolve(fixture + ".dm.json");
        try (InputStream in = MessageTokenSchemaClaimsTest.class.getResourceAsStream("/models/" + fixture + ".dm.json")) {
            Files.write(model, in.readAllBytes());
        }
        return run(dir, model, target, spec);
    }

    /** The {@code messages[].text} description of an add schema. */
    private static String description(String schema, String messages) throws Exception {
        try (InputStream in = MessageTokenSchemaClaimsTest.class.getResourceAsStream("/schema/" + schema + ".schema.json")) {
            JsonNode root = MAPPER.readTree(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            return root.path("properties").path(messages).path("items").path("properties").path("text")
                    .path("description").asText();
        }
    }
}
