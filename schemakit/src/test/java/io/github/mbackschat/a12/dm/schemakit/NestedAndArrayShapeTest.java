package io.github.mbackschat.a12.dm.schemakit;

import io.github.mbackschat.a12.dm.modelkit.ModelDocument;
import io.github.mbackschat.a12.dm.modelkit.Requiredness;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static io.github.mbackschat.a12.dm.schemakit.SchemaTestSupport.assertKernelAccepts;
import static io.github.mbackschat.a12.dm.schemakit.SchemaTestSupport.normalize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Shape 2 (SCHEMAKIT-SPEC §6): nested {@code object}s → nested groups and {@code array}-of-{@code
 * object} → repeatable groups round-trip; the structural asymmetries a12 cannot express ({@code minItems},
 * unbounded arrays) are reported, never silently mismapped.
 */
class NestedAndArrayShapeTest {

    private static final String NESTED_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "orderId": { "type": "string" },
                "shippingAddress": {
                  "type": "object",
                  "properties": {
                    "street": { "type": "string" },
                    "zip":    { "type": "string" }
                  },
                  "required": ["street"]
                },
                "lineItems": {
                  "type": "array",
                  "maxItems": 50,
                  "items": {
                    "type": "object",
                    "properties": {
                      "sku":      { "type": "string" },
                      "quantity": { "type": "integer" }
                    },
                    "required": ["sku"]
                  }
                }
              },
              "required": ["orderId"]
            }
            """;

    @Test
    void importsNestedAndRepeatableGroups() {
        JsonSchemaImporter.ImportResult imported = new JsonSchemaImporter().importSchema(NESTED_SCHEMA);
        ModelDocument doc = ModelDocument.read(imported.dmJson());

        // nested object → nested group
        assertEquals(Optional.of("STRING"), doc.fieldKind("/Root/shippingAddress/street"));
        assertEquals(Requiredness.REQUIRED,
                doc.requiredness("/Root/shippingAddress/street").orElse(Requiredness.OPTIONAL));

        // array-of-object → repeatable group (maxItems → repeatability)
        assertTrue(doc.isRepeating("/Root/lineItems"));
        assertEquals(50, doc.maxRepetitions("/Root/lineItems"));
        assertEquals(Optional.of("NUMBER"), doc.fieldKind("/Root/lineItems/quantity"));

        assertEquals(0, imported.report().diagnostics().size());
    }

    @Test
    void nestedAndRepeatableRoundTrip() {
        JsonSchemaImporter.ImportResult imported = new JsonSchemaImporter().importSchema(NESTED_SCHEMA);
        assertKernelAccepts(imported.dmJson());
        JsonSchemaExporter.ExportResult exported = new JsonSchemaExporter().export(imported.dmJson());

        assertEquals(normalize(NESTED_SCHEMA), normalize(exported.jsonSchema()),
                "nested object + array-of-object must round-trip");
    }

    @Test
    void minItemsIsReportedHavingNoStructuralHome() {
        String withMin = """
                {
                  "type": "object",
                  "properties": {
                    "rows": {
                      "type": "array", "minItems": 1, "maxItems": 10,
                      "items": { "type": "object", "properties": { "v": { "type": "string" } } }
                    }
                  }
                }
                """;
        JsonSchemaImporter.ImportResult imported = new JsonSchemaImporter().importSchema(withMin);

        assertTrue(imported.report().diagnostics().stream().anyMatch(d -> d.contains("minItems")),
                "minItems has no min-repetition slot in a12 — it must be reported");
        // the group itself still imports (maxItems maps); only the minItems lower bound is deferred
        assertTrue(ModelDocument.read(imported.dmJson()).isRepeating("/Root/rows"));
    }

    @Test
    void unboundedArrayIsOmitted() {
        String unbounded = """
                {
                  "type": "object",
                  "properties": {
                    "rows": {
                      "type": "array",
                      "items": { "type": "object", "properties": { "v": { "type": "string" } } }
                    }
                  }
                }
                """;
        JsonSchemaImporter.ImportResult imported = new JsonSchemaImporter().importSchema(unbounded);

        // a12 repeatability is bounded (≥2) — an unbounded array is omitted, not given an arbitrary cap,
        // so the group is never created …
        assertEquals(Optional.empty(), ModelDocument.read(imported.dmJson()).fieldKind("/Root/rows/v"));
        // … and the omission is recorded as a to-do (import still produced a model — never blocks)
        assertTrue(imported.report().dispositions().stream()
                .anyMatch(d -> d.tier() == TranscodeReport.Tier.OMITTED));
    }
}
