package io.github.mbackschat.a12.dm.schemakit;

import io.github.mbackschat.a12.dm.modelkit.ModelDocument;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static io.github.mbackschat.a12.dm.schemakit.SchemaTestSupport.assertKernelAccepts;
import static io.github.mbackschat.a12.dm.schemakit.SchemaTestSupport.normalize;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A JSON Schema {@code enum} imports to an a12 ENUMERATION and round-trips (SCHEMAKIT-SPEC §4). Labels have
 * no JSON-Schema source, so the default is none (the label strategy is a MappingProfile knob).
 */
class EnumShapeTest {

    private static final String ENUM_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "status": { "type": "string", "enum": ["DRAFT", "APPROVED", "SHIPPED"] }
              },
              "required": ["status"]
            }
            """;

    @Test
    void enumImportsToEnumeration() {
        JsonSchemaImporter.ImportResult imported = new JsonSchemaImporter().importSchema(ENUM_SCHEMA);
        assertKernelAccepts(imported.dmJson());
        ModelDocument doc = ModelDocument.read(imported.dmJson());
        assertEquals(Optional.of("ENUMERATION"), doc.fieldKind("/Root/status"));
    }

    @Test
    void enumRoundTrips() {
        JsonSchemaImporter.ImportResult imported = new JsonSchemaImporter().importSchema(ENUM_SCHEMA);
        JsonSchemaExporter.ExportResult exported = new JsonSchemaExporter().export(imported.dmJson());
        assertEquals(SchemaTestSupport.withDefaultRoot(ENUM_SCHEMA), normalize(exported.jsonSchema()),
                "a string enum must round-trip as {type:string, enum:[…]}");
    }
}
