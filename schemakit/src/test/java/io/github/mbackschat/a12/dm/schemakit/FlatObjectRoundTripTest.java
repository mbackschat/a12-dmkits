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
     * JSON {@code number} has no faithful a12 target (an a12 number is always scale-bounded, default 0 =
     * integer). The transcoder must REPORT that, never silently turn a decimal into an integer — the
     * never-silent-degrade discipline (SCHEMAKIT-SPEC §4).
     */
    @Test
    void unboundedNumberIsReportedNotSilentlyMismapped() {
        String withNumber = """
                {
                  "type": "object",
                  "properties": { "unitPrice": { "type": "number" } }
                }
                """;
        JsonSchemaImporter.ImportResult imported = new JsonSchemaImporter().importSchema(withNumber);

        ModelDocument doc = ModelDocument.read(imported.dmJson());
        assertEquals(Optional.empty(), doc.fieldKind("/Root/unitPrice"));
        assertEquals(1, imported.report().diagnostics().size());
        assertEquals(TranscodeReport.Tier.OMITTED, imported.report().dispositions().get(0).tier());
    }

    /**
     * The omit-never-block policy (SCHEMAKIT-SPEC §1): an unmappable element does NOT abort the import — the
     * mappable siblings still import into a kernel-valid skeleton, and the omission is a corrective to-do.
     */
    @Test
    void importNeverBlocksAMappableSiblingSurvivesAnOmission() {
        String mixed = """
                {
                  "type": "object",
                  "properties": {
                    "name":   { "type": "string" },
                    "price":  { "type": "number" },
                    "active": { "type": "boolean" }
                  },
                  "required": ["name"]
                }
                """;
        JsonSchemaImporter.ImportResult imported = new JsonSchemaImporter().importSchema(mixed);

        // the mappable fields import, the model is kernel-valid — import did not block on the omitted `price`
        ModelDocument doc = ModelDocument.read(imported.dmJson());
        assertEquals(Optional.of("STRING"), doc.fieldKind("/Root/name"));
        assertEquals(Optional.of("BOOLEAN"), doc.fieldKind("/Root/active"));
        assertEquals(Optional.empty(), doc.fieldKind("/Root/price"));
        assertKernelAccepts(imported.dmJson());
        // the omission is a recorded, corrective to-do
        assertTrue(imported.report().diagnostics().stream().anyMatch(d -> d.contains("TO DO")),
                "an omission must leave a corrective to-do in the report");
    }
}
