package io.github.mbackschat.a12.dm.schemakit.spike;

import org.junit.jupiter.api.Test;

import static io.github.mbackschat.a12.dm.schemakit.spike.SchemaTestSupport.assertKernelAccepts;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tier-A+ rule synthesis (SCHEMAKIT-SPEC §4): a JSON Schema keyword with no field-config home becomes a real
 * a12 rule on import, authored through the typed surface and kernel-accepted. Here: {@code exclusiveMinimum}
 * / {@code exclusiveMaximum} → strict-bound rules (a12 field bounds are inclusive-only).
 */
class RuleSynthesisShapeTest {

    @Test
    void exclusiveBoundsSynthesizeKernelAcceptedRules() {
        String schema = """
                {
                  "type": "object",
                  "properties": {
                    "ratio": { "type": "integer", "exclusiveMinimum": 0, "exclusiveMaximum": 100 }
                  }
                }
                """;
        JsonSchemaImporter.ImportResult imported = new JsonSchemaImporter().importSchema(schema);

        // the synthesized rules are kernel-valid …
        assertKernelAccepts(imported.dmJson());
        // … present as real conditions (error when the strict bound is violated) …
        assertTrue(imported.dmJson().contains("[ratio] <= 0"), "exclusiveMinimum → an error rule [ratio] <= 0");
        assertTrue(imported.dmJson().contains("[ratio] >= 100"), "exclusiveMaximum → an error rule [ratio] >= 100");
        // … and recorded as Tier-A+ dispositions (two of them)
        assertTrue(imported.report().dispositions().stream()
                        .filter(d -> d.tier() == TranscodeReport.Tier.TIER_A_PLUS).count() == 2,
                "both strict bounds are recorded as Tier-A+ synthesized rules");
    }

    @Test
    void dependentRequiredSynthesizesAConditionalRequirednessRule() {
        String schema = """
                {
                  "type": "object",
                  "properties": {
                    "card": { "type": "string" },
                    "cvv":  { "type": "string" }
                  },
                  "dependentRequired": { "card": ["cvv"] }
                }
                """;
        JsonSchemaImporter.ImportResult imported = new JsonSchemaImporter().importSchema(schema);

        assertKernelAccepts(imported.dmJson());
        // "if card is present, cvv is required" → error when card filled AND cvv not filled
        assertTrue(imported.dmJson().contains("FieldFilled(card)"));
        assertTrue(imported.dmJson().contains("FieldNotFilled(cvv)"));
        assertTrue(imported.report().dispositions().stream()
                        .anyMatch(d -> d.tier() == TranscodeReport.Tier.TIER_A_PLUS),
                "dependentRequired is a Tier-A+ synthesized rule");
    }

    @Test
    void constSynthesizesAnEqualityRule() {
        String schema = """
                {
                  "type": "object",
                  "properties": {
                    "kind":    { "type": "string",  "const": "ORDER" },
                    "version": { "type": "integer", "const": 1 }
                  }
                }
                """;
        JsonSchemaImporter.ImportResult imported = new JsonSchemaImporter().importSchema(schema);

        // both consts become kernel-valid equality rules (error when the value differs from the const)
        assertKernelAccepts(imported.dmJson());
        assertTrue(imported.dmJson().contains("CONST_KIND"), "string const → a CONST_KIND rule");
        assertTrue(imported.dmJson().contains("CONST_VERSION"), "integer const → a CONST_VERSION rule");
        assertTrue(imported.report().dispositions().stream()
                        .filter(d -> d.tier() == TranscodeReport.Tier.TIER_A_PLUS).count() == 2,
                "both consts are Tier-A+ synthesized rules");
    }

    @Test
    void multipleOfSynthesizesAModuloRule() {
        String schema = """
                {
                  "type": "object",
                  "properties": { "qty": { "type": "integer", "multipleOf": 5 } }
                }
                """;
        JsonSchemaImporter.ImportResult imported = new JsonSchemaImporter().importSchema(schema);

        assertKernelAccepts(imported.dmJson());
        // divisibility as a modulo rule: x − floor(x/N)·N ≠ 0  (a12 has no modulo operator)
        assertTrue(imported.dmJson().contains("RoundDown"), "multipleOf composes via RoundDown (floor)");
        assertTrue(imported.report().dispositions().stream()
                        .anyMatch(d -> d.tier() == TranscodeReport.Tier.TIER_A_PLUS),
                "multipleOf is a Tier-A+ synthesized rule");
    }
}
