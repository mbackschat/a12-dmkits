package io.github.mbackschat.a12.dm.schemakit.spike;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.mbackschat.a12.dm.modelkit.ModelDocument;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static io.github.mbackschat.a12.dm.schemakit.spike.SchemaTestSupport.assertKernelAccepts;
import static io.github.mbackschat.a12.dm.schemakit.spike.SchemaTestSupport.normalize;
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
    void constraintsImportToFieldConfig() {
        JsonSchemaImporter.ImportResult imported = new JsonSchemaImporter().importSchema(CONSTRAINED);
        assertKernelAccepts(imported.dmJson());
        ModelDocument doc = ModelDocument.read(imported.dmJson());

        ObjectNode codeCfg = doc.fieldConfig("/Root/code").orElseThrow();
        assertEquals(2, codeCfg.findValue("minLength").asInt());
        assertEquals(5, codeCfg.findValue("maxLength").asInt());
        assertEquals("[A-Z]+", codeCfg.findValue("pattern").asText());

        // the read-back number block carries the bounds as text under min/max
        ObjectNode qtyCfg = doc.fieldConfig("/Root/quantity").orElseThrow();
        assertEquals("1", qtyCfg.findValue("min").asText());
        assertEquals("999", qtyCfg.findValue("max").asText());
    }

    @Test
    void constraintsRoundTrip() {
        JsonSchemaImporter.ImportResult imported = new JsonSchemaImporter().importSchema(CONSTRAINED);
        JsonSchemaExporter.ExportResult exported = new JsonSchemaExporter().export(imported.dmJson());

        assertEquals(normalize(CONSTRAINED), normalize(exported.jsonSchema()),
                "minLength/maxLength/pattern + minimum/maximum must round-trip through field config");
    }
}
