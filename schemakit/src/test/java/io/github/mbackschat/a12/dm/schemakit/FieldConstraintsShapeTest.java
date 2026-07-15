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
