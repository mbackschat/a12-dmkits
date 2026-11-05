package io.github.mbackschat.a12.dm.schemakit;

import static io.github.mbackschat.a12.dm.schemakit.SchemaTestSupport.assertKernelAccepts;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.FieldTypeSpec;
import io.github.mbackschat.a12.dm.modelkit.ModelDocument;

/**
 * A12 identifies an enumeration's values and labels as {@link String#trim} leaves them, so {@code GOLD} and
 * {@code GOLD } are one value ({@code MVK_INTERNAL_VALUES_DUPLICATE}) and {@code Tier} and {@code Tier } one label
 * ({@code MVK_DISPLAY_VALUES_DUPLICATE}) — KERNEL-FINDINGS KF418's 2026-10-21 trimming rows. A schema may declare
 * both spellings, so import keeps the first value of each and leaves trim-equal labels off, reporting either; the
 * kernel gate in {@code assertKernelAccepts} is the oracle for every row. Own domain (insurance tiers and payments).
 */
class EnumTrimmedIdentityImportTest {

    @Test
    void labelsThatTrimToOneTextLeaveTheEnumerationUnlabelled() {
        var imported = importWith("""
                { "type": "object", "properties": {
                    "tier": { "type": "string", "enum": ["SILVER", "GOLD"], "x-enumNames": ["Tier", "Tier "] },
                    "plan": { "type": "string", "enum": ["BASIC", "FULL"], "x-enumNames": ["Plan", "\\tPlan"] } } }
                """, MappingProfile.EnumLabels.X_ENUM_NAMES);
        assertKernelAccepts(imported.dmJson());
        assertEquals(Map.of("SILVER", Map.of(), "GOLD", Map.of()), labels(imported.dmJson(), "/Root/tier"));
        assertEquals(Map.of("BASIC", Map.of(), "FULL", Map.of()), labels(imported.dmJson(), "/Root/plan"));
        for (String field : List.of("'tier'", "'plan'")) {
            assertReported(imported, field, "share one label");
        }
    }

    @Test
    void valuesA12ReadsAsOneKeepTheFirstAndAreReported() {
        var imported = importWith("""
                { "type": "object", "properties": {
                    "tier": { "type": "string", "enum": ["GOLD", "GOLD "] },
                    "plan": { "type": "string", "enum": ["BASIC", "BASIC"] } } }
                """, MappingProfile.EnumLabels.BLANK);
        assertKernelAccepts(imported.dmJson());
        assertEquals(List.of("GOLD"), List.copyOf(labels(imported.dmJson(), "/Root/tier").keySet()));
        assertEquals(List.of("BASIC"), List.copyOf(labels(imported.dmJson(), "/Root/plan").keySet()));
        assertReported(imported, "'tier'", "'GOLD '");
        assertReported(imported, "'plan'", "'BASIC'");
    }

    /** The names of {@code x-enumNames} stay paired with their values once a value is dropped. */
    @Test
    void aDroppedValueTakesItsDisplayNameWithIt() {
        var imported = importWith("""
                { "type": "object", "properties": {
                    "tier": { "type": "string", "enum": ["SILVER", "GOLD", " GOLD"],
                              "x-enumNames": ["Silver tier", "Gold tier", "Gold again"] } } }
                """, MappingProfile.EnumLabels.X_ENUM_NAMES);
        assertKernelAccepts(imported.dmJson());
        assertEquals(Map.of("SILVER", Map.of("en_US", "Silver tier"), "GOLD", Map.of("en_US", "Gold tier")),
                labels(imported.dmJson(), "/Root/tier"));
    }

    /** A union's discriminator is built from the variants' constants, the third path that adds enum values. */
    @Test
    void aDiscriminatorWhoseConstantsTrimToOneKeepsTheFirst() {
        var imported = importWith("""
                { "type": "object", "properties": { "payment": { "oneOf": [
                    { "type": "object", "required": ["method"], "properties": { "method": { "const": "CARD" } } },
                    { "type": "object", "required": ["method"], "properties": { "method": { "const": "CARD " } } },
                    { "type": "object", "required": ["method"], "properties": { "method": { "const": "BANK" } } } ] } } }
                """, MappingProfile.EnumLabels.BLANK);
        assertKernelAccepts(imported.dmJson());
        assertEquals(List.of("CARD", "BANK"), List.copyOf(labels(imported.dmJson(), "/Root/payment/method").keySet()));
    }

    private static JsonSchemaImporter.ImportResult importWith(String schema, MappingProfile.EnumLabels strategy) {
        return new JsonSchemaImporter().importSchema(schema, MappingProfile.defaults().withEnumLabels(strategy));
    }

    private static void assertReported(JsonSchemaImporter.ImportResult imported, String field, String detail) {
        assertTrue(imported.report().diagnostics().stream().anyMatch(d -> d.contains(field) && d.contains(detail)),
                () -> field + " / " + detail + ": " + imported.report().diagnostics());
    }

    private static Map<String, Map<String, String>> labels(String dmJson, String fieldPath) {
        var type = (FieldTypeSpec.EnumSpec) ModelDocument.read(dmJson).authoredState().fields().get(fieldPath).type();
        Map<String, Map<String, String>> out = new LinkedHashMap<>();
        type.values().forEach(v -> out.put(v.value(), v.labels()));
        return out;
    }
}
