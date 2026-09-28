package io.github.mbackschat.a12.dm.schemakit;

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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Shape 4b (SCHEMAKIT-SPEC §6; proposal §6 Tier A) — the project-or-carry split. A string pattern
 * rule is BOTH projected to a native JSON Schema {@code pattern} keyword (for a foreign reader) AND carried
 * as {@code x-a12-rule} (for a12 fidelity); the rule read is reused from {@code :adapter}. A rule with no
 * native home (a cross-field rule) is carried only.
 */
class TierAProjectionShapeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String PATTERN = "[0-9]{5}";

    @Test
    void indexedPatternIsCarriedWithoutProjectingAConstraintOnEveryRow() throws Exception {
        String structure = DocumentModelDraft.named("Order").locale("en_US")
                .rootGroup("Root", g -> g.field("Title", FieldKind.STRING)
                        .group("Items", i -> i.repeatable(5).indexField("Sku")
                                .field("Sku", FieldKind.STRING).field("Label", FieldKind.STRING)))
                .build().toJson();
        BoundModel m = BoundModel.ofJson(structure);
        StringRef title = m.str("/Root/Title");
        Rule rule = m.rule(m.group("/Root")).name("SelectedLabel")
                .errorField(title)
                .when(title.filled().and(Dsl.violates(
                        m.field("/Root/Items/Label").semanticIndex("S-1").index(), "[A-Z]+")))
                .code("LABEL_FORMAT").severity(Severity.ERROR)
                .message(Dsl.en("The selected item label must be uppercase."))
                .build();
        JsonSchemaExporter.ExportResult exported = new JsonSchemaExporter().export(m.add(rule).toJson());
        JsonNode label = MAPPER.readTree(exported.jsonSchema()).path("properties").path("Items")
                .path("items").path("properties").path("Label");
        assertFalse(label.has("pattern"), "a selected row does not constrain every item label");
        assertTrue(exported.jsonSchema().contains(Carriage.X_RULE));
    }

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
    void aNonIntegerGroupCountBoundDoesNotCrashTheExport() throws Exception {
        // F8 — TierAProjection called intValueExact on the NumberOfFilledGroups(G*) < N bound of ANY exported
        // rule; a kernel-legal but non-integer bound (2.5) threw ArithmeticException, aborting the whole export.
        String structure = DocumentModelDraft.named("Order").locale("en_US")
                .rootGroup("Root", g -> g.group("Items", i -> {
                    i.repeatable(10);
                    i.field("Sku", FieldKind.STRING);
                }))
                .build().toJson();
        BoundModel m = BoundModel.ofJson(structure);
        Rule rule = m.rule(m.group("/Root"))
                .name("MinLines").errorField(m.str("/Root/Items/Sku"))
                .when(Dsl.numberOfFilledGroups(m.group("/Root/Items")).lt("2.5"))
                .code("MIN_LINES").severity(Severity.ERROR).message(Dsl.en("Too few lines.")).build();
        String dm = m.add(rule).toJson();

        JsonSchemaExporter.ExportResult exported = new JsonSchemaExporter().export(dm);   // must NOT throw
        JsonNode schema = MAPPER.readTree(exported.jsonSchema());
        assertFalse(schema.path("properties").path("Items").has("minItems"),
                () -> "a non-integer group-count bound is not projectable as minItems: " + exported.jsonSchema());
        assertTrue(exported.report().diagnostics().stream().anyMatch(d -> d.contains("minItems")),
                () -> "the skipped minItems projection is reported: " + exported.report().diagnostics());
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
