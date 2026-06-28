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
 * Shape 1 (SCHEMAKIT-SPEC §6): a flat top-level object round-trips, and every imported model is
 * kernel-accepted — the accept gate rides the public facade (`ModelLoader`), so the structural shapes need
 * no internal tier (the dependency boundary is SCHEMAKIT-SPEC §1).
 */
class FlatObjectRoundTripTest {

    private static final String FLAT_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "customerName": { "type": "string" },
                "quantity":     { "type": "integer" },
                "express":      { "type": "boolean" }
              },
              "required": ["customerName", "quantity"]
            }
            """;

    @Test
    void importsFlatFieldsWithRequiredness() {
        JsonSchemaImporter.ImportResult imported = new JsonSchemaImporter().importSchema(FLAT_SCHEMA);

        ModelDocument doc = ModelDocument.read(imported.dmJson());
        assertEquals(Optional.of("STRING"), doc.fieldKind("/Root/customerName"));
        assertEquals(Optional.of("NUMBER"), doc.fieldKind("/Root/quantity"));
        assertEquals(Optional.of("BOOLEAN"), doc.fieldKind("/Root/express"));

        assertEquals(Requiredness.REQUIRED, doc.requiredness("/Root/customerName").orElse(Requiredness.OPTIONAL));
        assertEquals(Requiredness.REQUIRED, doc.requiredness("/Root/quantity").orElse(Requiredness.OPTIONAL));
        assertEquals(Requiredness.OPTIONAL, doc.requiredness("/Root/express").orElse(Requiredness.OPTIONAL));

        // the report states every disposition — its accuracy IS the report's evidence (SCHEMAKIT-SPEC §5)
        assertEquals(3, imported.report().dispositions().size());
        assertEquals(0, imported.report().diagnostics().size());
    }

    @Test
    void importedModelIsKernelAccepted() {
        assertKernelAccepts(new JsonSchemaImporter().importSchema(FLAT_SCHEMA).dmJson());
    }

    @Test
    void flatObjectRoundTrips() {
        JsonSchemaImporter.ImportResult imported = new JsonSchemaImporter().importSchema(FLAT_SCHEMA);
        JsonSchemaExporter.ExportResult exported = new JsonSchemaExporter().export(imported.dmJson());

        assertEquals(normalize(FLAT_SCHEMA), normalize(exported.jsonSchema()),
                "import → export must reproduce {type, properties, required}");
    }

    /**
     * JSON {@code number} has no scale signal (an a12 number is always scale-bounded). The best-effort default
     * imports it at a default scale rather than dropping it — and the scale is a GUESS, so it is flagged in
     * the report (never silent; SCHEMAKIT-SPEC §4b). {@code --number-scale OMIT} / {@code --strict} drops it.
     */
    @Test
    void unboundedNumberIsImportedAtTheDefaultScaleAndFlagged() {
        String withNumber = """
                {
                  "type": "object",
                  "properties": { "unitPrice": { "type": "number" } }
                }
                """;
        JsonSchemaImporter.ImportResult imported = new JsonSchemaImporter().importSchema(withNumber);

        ModelDocument doc = ModelDocument.read(imported.dmJson());
        assertEquals(Optional.of("NUMBER"), doc.fieldKind("/Root/unitPrice"), "best-effort imports it");
        assertEquals(2, doc.fieldConfig("/Root/unitPrice").orElseThrow().findValue("maxFractionalDigits").asInt());
        assertTrue(imported.report().diagnostics().stream().anyMatch(d -> d.contains("scale")),
                () -> "the guessed scale is flagged, not silent: " + imported.report().diagnostics());
    }

    /**
     * The omit-never-block policy (SCHEMAKIT-SPEC §4a): an unmappable element does NOT abort the import — the
     * mappable siblings still import into a kernel-valid skeleton, and the omission is a corrective to-do. The
     * omitted sibling here is a cross-kind type union (no a12 form even best-effort — §4c).
     */
    @Test
    void importNeverBlocksAMappableSiblingSurvivesAnOmission() {
        String mixed = """
                {
                  "type": "object",
                  "properties": {
                    "name":   { "type": "string" },
                    "weird":  { "type": ["string", "object"] },
                    "active": { "type": "boolean" }
                  },
                  "required": ["name"]
                }
                """;
        JsonSchemaImporter.ImportResult imported = new JsonSchemaImporter().importSchema(mixed);

        // the mappable fields import, the model is kernel-valid — import did not block on the omitted union
        ModelDocument doc = ModelDocument.read(imported.dmJson());
        assertEquals(Optional.of("STRING"), doc.fieldKind("/Root/name"));
        assertEquals(Optional.of("BOOLEAN"), doc.fieldKind("/Root/active"));
        assertEquals(Optional.empty(), doc.fieldKind("/Root/weird"), "the cross-kind union has no a12 form");
        assertKernelAccepts(imported.dmJson());
        // the omission is a recorded, corrective to-do
        assertTrue(imported.report().diagnostics().stream().anyMatch(d -> d.contains("TO DO")),
                "an omission must leave a corrective to-do in the report");
    }
}
