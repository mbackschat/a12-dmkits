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
}
