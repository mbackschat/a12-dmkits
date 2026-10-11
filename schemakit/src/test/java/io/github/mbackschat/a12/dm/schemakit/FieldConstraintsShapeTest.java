package io.github.mbackschat.a12.dm.schemakit;

import io.github.mbackschat.a12.dm.modelkit.ModelDocument;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.OptionalInt;

import static io.github.mbackschat.a12.dm.schemakit.SchemaTestSupport.assertKernelAccepts;
import static io.github.mbackschat.a12.dm.schemakit.SchemaTestSupport.normalize;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Field-local constraints import to a12 field config (not just the type) and round-trip:
 * {@code minLength}/{@code maxLength}/{@code pattern} → string config, {@code minimum}/{@code maximum} →
 * number bounds (SCHEMAKIT-SPEC §4 "Field config").
 */
class FieldConstraintsShapeTest {

    private static final String CONSTRAINED = """
            {
              "type": "object",
              "properties": {
                "code":     { "type": "string", "minLength": 2, "maxLength": 5, "pattern": "[A-Z]+" },
                "quantity": { "type": "integer", "minimum": 1, "maximum": 999 }
              }
            }
            """;

    @Test
    void composedEmptyPatternsNeverGenerateAnExecutingEmptyPattern() {
        for (String arm : java.util.List.of("then", "else")) {
            var imported = new JsonSchemaImporter().importSchema("""
                    {"type":"object","properties":{"mode":{"type":"string"},"code":{"type":"string"}},
                     "if":{"properties":{"mode":{"const":"active"}}},"%s":{"properties":{"code":{"pattern":""}}}}
                    """.formatted(arm));
            assertKernelAccepts(imported.dmJson());
            assertEquals(0, ModelDocument.read(imported.dmJson()).ruleCount());
            org.junit.jupiter.api.Assertions.assertTrue(imported.report().dispositions().stream()
                    .anyMatch(d -> d.tier() == TranscodeReport.Tier.TIER_A_PLUS));
        }
        var forbidden = new JsonSchemaImporter().importSchema("""
                {"type":"object","properties":{"code":{"type":"string","not":{"pattern":""}}}}
                """);
        assertKernelAccepts(forbidden.dmJson());
        assertEquals(0, ModelDocument.read(forbidden.dmJson()).ruleCount());
        org.junit.jupiter.api.Assertions.assertTrue(forbidden.report().diagnostics().stream()
                .anyMatch(d -> d.contains("empty") && d.contains("absent")));
        org.junit.jupiter.api.Assertions.assertTrue(forbidden.report().dispositions().stream()
                .anyMatch(d -> d.tier() == TranscodeReport.Tier.OMITTED));
    }

    @Test
    void universalContainsCountsRowsAndMixedContainsUsesTheRemainingAnchor() {
        for (boolean mixed : java.util.List.of(false, true)) {
            var imported = new JsonSchemaImporter().importSchema("""
                    {"type":"object","required":["legs"],"properties":{"legs":{"type":"array","maxItems":3,
                     "items":{"type":"object","properties":{"label":{"type":"string"},"kind":{"type":"string"}}},
                     "contains":{"properties":{"label":{"pattern":""}%s}},"minContains":1,"maxContains":2}}}
                    """.formatted(mixed ? ",\"kind\":{\"const\":\"express\"}" : ""));
            assertKernelAccepts(imported.dmJson());
            org.junit.jupiter.api.Assertions.assertFalse(imported.dmJson().contains("PatternMatched"));
            org.junit.jupiter.api.Assertions.assertTrue(imported.dmJson().contains(mixed ? "legs*/kind" : "NumberOfFilledGroups"));
        }
    }

    @Test
    void anEmptyPatternRoundTripsWithoutInventingAPatternMessage() {
        String schema = "{\"type\":\"object\",\"properties\":{\"code\":{\"type\":\"string\",\"pattern\":\"\"}}}";
        var imported = new JsonSchemaImporter().importSchema(schema);
        assertKernelAccepts(imported.dmJson());
        var exported = new JsonSchemaExporter().export(imported.dmJson());
        assertEquals(normalize(schema), normalize(exported.jsonSchema()));
    }

    @Test
    void constraintsImportToFieldConfig() {
        JsonSchemaImporter.ImportResult imported = new JsonSchemaImporter().importSchema(CONSTRAINED);
        assertKernelAccepts(imported.dmJson());
        ModelDocument doc = ModelDocument.read(imported.dmJson());

        assertEquals(OptionalInt.of(2), doc.fieldMinLength("/Root/code"));
        assertEquals(OptionalInt.of(5), doc.fieldMaxLength("/Root/code"));
        assertEquals(Optional.of("[A-Z]+"), doc.fieldPattern("/Root/code"));

        assertEquals(Optional.of(new BigDecimal("1")), doc.fieldMinValue("/Root/quantity"));
        assertEquals(Optional.of(new BigDecimal("999")), doc.fieldMaxValue("/Root/quantity"));
    }

    @Test
    void constraintsRoundTrip() {
        JsonSchemaImporter.ImportResult imported = new JsonSchemaImporter().importSchema(CONSTRAINED);
        JsonSchemaExporter.ExportResult exported = new JsonSchemaExporter().export(imported.dmJson());

        assertEquals(normalize(CONSTRAINED), normalize(exported.jsonSchema()),
                "minLength/maxLength/pattern + minimum/maximum must round-trip through field config");
    }
}
