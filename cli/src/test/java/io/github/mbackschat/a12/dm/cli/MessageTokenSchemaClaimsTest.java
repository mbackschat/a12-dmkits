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

/**
 * The message-token claims of the rule and computation add schemas that their own {@code examples} cannot carry,
 * executed through the tool (CLI-CONFORMANCE-GAPS CG23). The examples run on the subscription fixture, which keys
 * no group, so the keyed field tokens run here on the keyed claims fixture; the root-group refusal is a refusal,
 * which an example, being required to pass, cannot show. Each row also asserts that the schema still names what
 * it ran, so the description and the behaviour cannot part.
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

    private static CliTestSupport.Result add(Path dir, String fixture, String target, String spec) throws Exception {
        Path model = dir.resolve(fixture + ".dm.json");
        try (InputStream in = MessageTokenSchemaClaimsTest.class.getResourceAsStream("/models/" + fixture + ".dm.json")) {
            Files.write(model, in.readAllBytes());
        }
        Path specFile = Files.createTempFile(dir, target, ".json");
        Files.writeString(specFile, spec);
        return CliTestSupport.run("-m", model.toString(), target, "add", specFile.toString(), "--dry-run");
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
