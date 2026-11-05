package io.github.mbackschat.a12.dm.schemakit;

import static io.github.mbackschat.a12.dm.schemakit.SchemaTestSupport.assertKernelAccepts;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldTypeSpec;
import io.github.mbackschat.a12.dm.modelkit.ModelDocument;

/**
 * An enumeration's labels cross the bridge (SCHEMAKIT-SPEC §4). JSON Schema has no keyword for a per-locale label,
 * so export carries an a12 field's labels in {@code x-a12-enum-labels}, value by value in A12's own
 * {@code [{locale, text}]} shape, and import restores exactly that. The carriage is left out where the default import
 * would rebuild the same labels, so a foreign enum's import → export gains nothing ({@code EnumShapeTest}). A foreign
 * schema has no carriage, and {@code --enum-labels} decides: {@code DERIVE} labels each value with itself,
 * {@code X_ENUM_NAMES} reads the {@code x-enumNames} display-name array that react-jsonschema-form and NJsonSchema
 * write beside {@code enum}, and {@code BLANK} leaves values unlabelled. Own domain (insurance policies).
 */
class EnumLabelCarriageTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Two locales, a bilingual enum and an unlabelled one. A12 admits no partly labelled enum (KERNEL-FINDINGS
     *  KF321), so there is none to carry. */
    private static String policyModel() {
        return DocumentModelDraft.named("policy").locale("en_US").locale("de_DE")
                .rootGroup("Policy", p -> p
                        .enumField("Cover", e -> e
                                .value("BASIC", l -> l.label("en_US", "Basic cover").label("de_DE", "Grunddeckung"))
                                .value("FULL", l -> l.label("en_US", "Full cover").label("de_DE", "Volldeckung")))
                        .enumField("Channel", e -> e.value("ONLINE").value("BROKER")))
                .build().toJson();
    }

    private static Map<String, Map<String, String>> labels(String dmJson, String fieldPath) {
        var type = (FieldTypeSpec.EnumSpec) ModelDocument.read(dmJson).authoredState().fields().get(fieldPath).type();
        Map<String, Map<String, String>> out = new LinkedHashMap<>();
        type.values().forEach(v -> out.put(v.value(), v.labels()));
        return out;
    }

    private static JsonSchemaImporter.ImportResult importWith(String schema, MappingProfile.EnumLabels strategy) {
        return new JsonSchemaImporter().importSchema(schema, MappingProfile.defaults().withEnumLabels(strategy));
    }

    @Test
    void anA12ModelsLabelsSurviveTheRoundTripExactly() throws Exception {
        String source = policyModel();
        var exported = new JsonSchemaExporter().export(source);
        JsonNode properties = JSON.readTree(exported.jsonSchema()).findValue("properties");
        assertEquals("{\"BASIC\":[{\"locale\":\"en_US\",\"text\":\"Basic cover\"},{\"locale\":\"de_DE\",\"text\":"
                + "\"Grunddeckung\"}],\"FULL\":[{\"locale\":\"en_US\",\"text\":\"Full cover\"},{\"locale\":\"de_DE\","
                + "\"text\":\"Volldeckung\"}]}", properties.path("Cover").path(Carriage.X_ENUM_LABELS).toString());
        assertEquals("{}", properties.path("Channel").path(Carriage.X_ENUM_LABELS).toString(),
                "an unlabelled enum says so, or the default import would derive labels it never had");
        assertTrue(exported.report().dispositions().contains(
                new TranscodeReport.Disposition("/Policy/Cover", Carriage.X_ENUM_LABELS, TranscodeReport.Tier.CARRIED)));

        for (MappingProfile.EnumLabels strategy : MappingProfile.EnumLabels.values()) {
            var imported = importWith(exported.jsonSchema(), strategy);
            assertKernelAccepts(imported.dmJson());
            for (String field : List.of("Cover", "Channel")) {
                assertEquals(labels(source, "/Policy/" + field), labels(imported.dmJson(), "/Policy/" + field),
                        field + " under " + strategy + ": the carriage wins over the strategy");
            }
        }
    }

    /** Labels the default import rebuilds are left out; a model in the import's one locale is the only one with them. */
    @Test
    void labelsTheDefaultImportRebuildsAreNotCarried() throws Exception {
        String source = DocumentModelDraft.named("policy").locale("en_US")
                .rootGroup("Policy", p -> p.enumField("Term", e -> e
                        .value("ANNUAL", l -> l.label("en_US", "ANNUAL")).value("MONTHLY", l -> l.label("en_US", "MONTHLY"))))
                .build().toJson();
        var exported = new JsonSchemaExporter().export(source);
        assertFalse(JSON.readTree(exported.jsonSchema()).findValue("Term").has(Carriage.X_ENUM_LABELS),
                exported.jsonSchema());
        var imported = importWith(exported.jsonSchema(), MappingProfile.EnumLabels.DERIVE);
        assertKernelAccepts(imported.dmJson());
        assertEquals(labels(source, "/Policy/Term"), labels(imported.dmJson(), "/Policy/Term"));
    }

    private static final String FOREIGN = """
            { "type": "object", "properties": {
                "tier": { "type": "string", "enum": ["SILVER", "GOLD"], "x-enumNames": ["Silver tier", "Gold tier"] },
                "claim": { "type": "string", "enum": ["OPEN", "CLOSED"] },
                "addOns": { "type": "array", "items": { "type": "string", "enum": ["GLASS", "THEFT"],
                    "x-enumNames": ["Glass cover", "Theft cover"] } },
                "payment": { "oneOf": [
                    { "type": "object", "required": ["method"], "properties": { "method": { "const": "CARD" } } },
                    { "type": "object", "required": ["method"], "properties": { "method": { "const": "BANK" } } } ] } } }
            """;

    @Test
    void aForeignSchemasLabelsFollowTheStrategy() {
        var derived = importWith(FOREIGN, MappingProfile.EnumLabels.DERIVE);
        assertEquals(Map.of("SILVER", Map.of("en_US", "SILVER"), "GOLD", Map.of("en_US", "GOLD")),
                labels(derived.dmJson(), "/Root/tier"));
        assertEquals(Map.of("CARD", Map.of("en_US", "CARD"), "BANK", Map.of("en_US", "BANK")),
                labels(derived.dmJson(), "/Root/payment/method"), "a discriminator is an enumeration too");

        var named = new JsonSchemaImporter().importSchema(FOREIGN, MappingProfile.defaults()
                .withEnumLabels(MappingProfile.EnumLabels.X_ENUM_NAMES)
                .withArrayOfScalar(MappingProfile.ArrayOfScalar.MULTI_SELECT));
        assertKernelAccepts(named.dmJson());
        assertEquals(Map.of("SILVER", Map.of("en_US", "Silver tier"), "GOLD", Map.of("en_US", "Gold tier")),
                labels(named.dmJson(), "/Root/tier"));
        assertEquals(Map.of("GLASS", Map.of("en_US", "Glass cover"), "THEFT", Map.of("en_US", "Theft cover")),
                labels(named.dmJson(), "/Root/addOns/value"), "a multi-select's choices read items.x-enumNames");
        assertEquals(Map.of("OPEN", Map.of(), "CLOSED", Map.of()), labels(named.dmJson(), "/Root/claim"));
        assertTrue(named.report().diagnostics().stream().anyMatch(d -> d.contains("'claim'") && d.contains("x-enumNames")),
                () -> "a missing x-enumNames is reported, never silently derived: " + named.report().diagnostics());

        var blank = importWith(FOREIGN, MappingProfile.EnumLabels.BLANK);
        assertEquals(Map.of("SILVER", Map.of(), "GOLD", Map.of()), labels(blank.dmJson(), "/Root/tier"));
    }

    /** Labels A12 would refuse are never derived: an empty token, a blank name, a name used twice (KF321). */
    @Test
    void labelsA12WouldRefuseLeaveTheEnumerationUnlabelledAndAreReported() {
        String schema = """
                { "type": "object", "properties": {
                    "excess": { "type": "string", "enum": ["", "LOW"] },
                    "tier": { "type": "string", "enum": ["SILVER", "GOLD"], "x-enumNames": ["Tier", "Tier"] },
                    "claim": { "type": "string", "enum": ["OPEN", "CLOSED"], "x-enumNames": ["Open", " "] } } }
                """;
        var derived = importWith(schema, MappingProfile.EnumLabels.DERIVE);
        assertKernelAccepts(derived.dmJson());
        assertEquals(Map.of("", Map.of(), "LOW", Map.of()), labels(derived.dmJson(), "/Root/excess"));
        var named = importWith(schema, MappingProfile.EnumLabels.X_ENUM_NAMES);
        assertKernelAccepts(named.dmJson());
        assertEquals(Map.of("SILVER", Map.of(), "GOLD", Map.of()), labels(named.dmJson(), "/Root/tier"));
        assertEquals(Map.of("OPEN", Map.of(), "CLOSED", Map.of()), labels(named.dmJson(), "/Root/claim"));
        for (String field : List.of("'tier'", "'claim'")) {
            assertTrue(named.report().diagnostics().stream().anyMatch(d -> d.contains(field) && d.contains("unlabelled")),
                    () -> field + ": " + named.report().diagnostics());
        }
        assertTrue(derived.report().diagnostics().stream().anyMatch(d -> d.contains("'excess'") && d.contains("empty value")),
                () -> derived.report().diagnostics().toString());
    }

    @Test
    void anXEnumNamesArrayThatDoesNotMatchTheValuesIsReportedAndNotUsed() {
        String schema = """
                { "type": "object", "properties": {
                    "tier": { "type": "string", "enum": ["SILVER", "GOLD"], "x-enumNames": ["Silver tier"] } } }
                """;
        var imported = importWith(schema, MappingProfile.EnumLabels.X_ENUM_NAMES);
        assertEquals(Map.of("SILVER", Map.of(), "GOLD", Map.of()), labels(imported.dmJson(), "/Root/tier"));
        assertTrue(imported.report().diagnostics().stream().anyMatch(d -> d.contains("'tier'") && d.contains("x-enumNames")),
                () -> imported.report().diagnostics().toString());
    }
}
