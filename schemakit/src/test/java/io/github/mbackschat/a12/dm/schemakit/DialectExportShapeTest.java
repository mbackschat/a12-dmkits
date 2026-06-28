package io.github.mbackschat.a12.dm.schemakit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OpenAPI dialect support on export (SCHEMAKIT-SPEC §4g): {@code --wrap-openapi} emits a minimal OpenAPI
 * document with the schema under {@code components/schemas} (refs → {@code #/components/schemas/…}); the bare
 * form keeps {@code $defs}. The {@code x-a12-*} carriage is a valid OpenAPI extension in every version.
 */
class DialectExportShapeTest {

    private static final String SCHEMA_WITH_TYPEDEF = """
            { "type": "object", "title": "Customer", "$defs": { "Email": { "type": "string" } },
              "properties": { "contact": { "$ref": "#/$defs/Email" } } }
            """;

    @Test
    void wrapOpenApiEmitsComponentsSchemasAndRefs() {
        String dm = new JsonSchemaImporter().importSchema(SCHEMA_WITH_TYPEDEF).dmJson();

        JsonSchemaExporter.ExportResult r = new JsonSchemaExporter().export(dm, Dialect.OPENAPI_31, true);

        assertTrue(r.jsonSchema().contains("\"openapi\""), "wrapped as an OpenAPI document");
        assertTrue(r.jsonSchema().contains("\"components\"") && r.jsonSchema().contains("\"schemas\""),
                "the schema lands under components/schemas");
        assertTrue(r.jsonSchema().contains("#/components/schemas/Email"), "the typedef ref points at components/schemas");
        assertFalse(r.jsonSchema().contains("#/$defs/"), "no $defs refs in the wrapped OpenAPI form");
    }

    @Test
    void bareExportKeepsDefsAndReportsThe30Caveat() {
        String dm = new JsonSchemaImporter().importSchema(SCHEMA_WITH_TYPEDEF).dmJson();

        JsonSchemaExporter.ExportResult r = new JsonSchemaExporter().export(dm, Dialect.OPENAPI_30, false);

        assertTrue(r.jsonSchema().contains("#/$defs/Email"), "the bare form keeps $defs");
        assertTrue(r.report().diagnostics().stream().anyMatch(d -> d.contains("3.0")),
                () -> "OpenAPI 3.0 + $defs is reported: " + r.report().diagnostics());
    }
}
