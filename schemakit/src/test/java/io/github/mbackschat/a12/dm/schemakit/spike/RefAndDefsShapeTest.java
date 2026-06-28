package io.github.mbackschat.a12.dm.schemakit.spike;

import io.github.mbackschat.a12.dm.modelkit.ModelDocument;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static io.github.mbackschat.a12.dm.schemakit.spike.SchemaTestSupport.assertKernelAccepts;
import static io.github.mbackschat.a12.dm.schemakit.spike.SchemaTestSupport.normalize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spike shape 3 (SCHEMAKIT-SPEC §6, proposal §4.1): a leaf {@code $def} ↔ a12 typeDefinition round-trips
 * via {@code $ref}; an object {@code $def} is inlined (with a reported reuse-collapse); and a
 * <em>recursive</em> {@code $ref} is REFUSED with a corrective diagnostic — the gate-and-refuse discipline.
 */
class RefAndDefsShapeTest {

    private static final String LEAF_TYPEDEF_SCHEMA = """
            {
              "type": "object",
              "$defs": {
                "Money": { "type": "integer" }
              },
              "properties": {
                "label": { "type": "string" },
                "price": { "$ref": "#/$defs/Money" },
                "tax":   { "$ref": "#/$defs/Money" }
              },
              "required": ["price"]
            }
            """;

    @Test
    void leafDefBecomesTypedefAndRoundTrips() {
        JsonSchemaImporter.ImportResult imported = new JsonSchemaImporter().importSchema(LEAF_TYPEDEF_SCHEMA);

        ModelDocument doc = ModelDocument.read(imported.dmJson());
        assertTrue(doc.typeDefinitionIds().contains("Money"), "leaf $def → a reusable typeDefinition");
        // both fields resolve to the typedef's underlying kind
        assertEquals(Optional.of("NUMBER"), doc.fieldKind("/Root/price"));
        assertEquals(Optional.of("NUMBER"), doc.fieldKind("/Root/tax"));
        assertKernelAccepts(imported.dmJson());

        JsonSchemaExporter.ExportResult exported = new JsonSchemaExporter().export(imported.dmJson());
        assertEquals(normalize(LEAF_TYPEDEF_SCHEMA), normalize(exported.jsonSchema()),
                "a leaf $def must round-trip as $defs + $ref (reuse preserved)");
    }

    @Test
    void objectDefIsInlinedAndReported() {
        String objectDef = """
                {
                  "type": "object",
                  "$defs": {
                    "Address": {
                      "type": "object",
                      "properties": { "street": { "type": "string" } }
                    }
                  },
                  "properties": {
                    "billing":  { "$ref": "#/$defs/Address" },
                    "shipping": { "$ref": "#/$defs/Address" }
                  }
                }
                """;
        JsonSchemaImporter.ImportResult imported = new JsonSchemaImporter().importSchema(objectDef);

        ModelDocument doc = ModelDocument.read(imported.dmJson());
        // both $ref sites inlined into their own group with the def's fields
        assertEquals(Optional.of("STRING"), doc.fieldKind("/Root/billing/street"));
        assertEquals(Optional.of("STRING"), doc.fieldKind("/Root/shipping/street"));
        assertKernelAccepts(imported.dmJson());
        // the reuse collapse is reported (once per inline site), never silent
        assertEquals(2, imported.report().diagnostics().stream().filter(d -> d.contains("INLINED")).count());
    }

    @Test
    void recursiveRefIsRefused() {
        String recursive = """
                {
                  "type": "object",
                  "$defs": {
                    "Node": {
                      "type": "object",
                      "properties": {
                        "value": { "type": "string" },
                        "child": { "$ref": "#/$defs/Node" }
                      }
                    }
                  },
                  "properties": {
                    "root": { "$ref": "#/$defs/Node" }
                  }
                }
                """;
        JsonSchemaImporter.ImportResult imported = new JsonSchemaImporter().importSchema(recursive);

        ModelDocument doc = ModelDocument.read(imported.dmJson());
        // the non-recursive part imports …
        assertEquals(Optional.of("STRING"), doc.fieldKind("/Root/root/value"));
        // … the recursive child is refused (not infinitely expanded), and the model still loads
        assertEquals(Optional.empty(), doc.fieldKind("/Root/root/child/value"));
        assertKernelAccepts(imported.dmJson());
        assertTrue(imported.report().dispositions().stream()
                        .anyMatch(d -> d.tier() == TranscodeReport.Tier.REFUSED),
                "a recursive $ref must be refused, not expanded");
        assertTrue(imported.report().diagnostics().stream().anyMatch(d -> d.contains("recursive")));
    }
}
