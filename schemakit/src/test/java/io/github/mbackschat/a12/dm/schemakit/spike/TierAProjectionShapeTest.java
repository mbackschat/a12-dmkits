package io.github.mbackschat.a12.dm.schemakit.spike;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.Rule;
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity;
import io.github.mbackschat.a12.dm.rulekit.dsl.StringRef;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spike shape 4b (SCHEMAKIT-SPEC §6; proposal §6 Tier A) — the project-or-carry split. A string pattern
 * rule is BOTH projected to a native JSON Schema {@code pattern} keyword (for a foreign reader) AND carried
 * as {@code x-a12-rule} (for a12 fidelity); the rule read is reused from {@code :adapter}. A rule with no
 * native home (a cross-field rule) is carried only.
 */
class TierAProjectionShapeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String PATTERN = "[0-9]{5}";

    private static String modelWithPatternRule() {
        String structure = DocumentModelDraft.named("Order")
                .locale("en_US")
                .rootGroup("Root", g -> g.field("PostalCode", FieldKind.STRING))
                .build()
                .toJson();
        BoundModel m = BoundModel.ofJson(structure);
        StringRef postal = m.str("/Root/PostalCode");
        Rule rule = m.rule(m.group("/Root"))
                .name("PostalCodeFormat")
                .errorField(postal)
                .when(postal.filled().and(postal.violates(PATTERN)))
                .code("POSTAL_CODE_MALFORMED")
                .severity(Severity.ERROR)
                .message(Dsl.en("The postal code must be exactly five digits."))
                .build();
        return m.add(rule).toJson();
    }

    @Test
    void patternRuleIsProjectedToNativeKeywordAndAlsoCarried() throws Exception {
        JsonSchemaExporter.ExportResult exported = new JsonSchemaExporter().export(modelWithPatternRule());
        JsonNode schema = MAPPER.readTree(exported.jsonSchema());

        // projected for a foreign reader: a native `pattern` keyword on the field
        assertEquals(PATTERN, schema.path("properties").path("PostalCode").path("pattern").asText(),
                "the pattern rule must project to a native JSON Schema pattern keyword");
        // and still carried for a12 fidelity
        assertTrue(exported.jsonSchema().contains(Carriage.X_RULE), "the rule is also carried as x-a12-rule");
        // the report records the Tier-A projection
        assertTrue(exported.report().dispositions().stream()
                        .anyMatch(d -> d.tier() == TranscodeReport.Tier.TIER_A),
                "the projection is recorded as a Tier-A disposition");
    }

    @Test
    void projectionIsAdditiveAndRoundTripStillLossless() throws Exception {
        String dm = modelWithPatternRule();
        JsonSchemaExporter.ExportResult first = new JsonSchemaExporter().export(dm);
        JsonSchemaImporter.ImportResult back = new JsonSchemaImporter().importSchema(first.jsonSchema());
        SchemaTestSupport.assertKernelAccepts(back.dmJson());
        JsonSchemaExporter.ExportResult second = new JsonSchemaExporter().export(back.dmJson());

        // the native pattern keyword is re-derived deterministically and the carried rule survives →
        // schema → DM → schema is lossless even with the additive Tier-A projection
        assertEquals(SchemaTestSupport.normalize(first.jsonSchema()),
                SchemaTestSupport.normalize(second.jsonSchema()),
                "the additive Tier-A projection must round-trip (pattern re-derived; rule carried)");
    }
}
