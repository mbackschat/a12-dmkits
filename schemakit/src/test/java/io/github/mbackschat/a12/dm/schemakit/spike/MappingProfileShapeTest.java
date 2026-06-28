package io.github.mbackschat.a12.dm.schemakit.spike;

import io.github.mbackschat.a12.dm.modelkit.ModelDocument;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static io.github.mbackschat.a12.dm.schemakit.spike.SchemaTestSupport.assertKernelAccepts;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The MappingProfile (SCHEMAKIT-SPEC §4b) makes choice-driven import decisions overridable. The default
 * preserves the omit-never-block behavior; a custom profile changes the outcome. Verified here for the two
 * decisions wired so far: number-scale and boolean kind.
 */
class MappingProfileShapeTest {

    private static final String NUMBER_SCHEMA = """
            { "type": "object", "properties": { "price": { "type": "number" } } }
            """;
    private static final String BOOL_SCHEMA = """
            { "type": "object", "properties": { "agreed": { "type": "boolean" } } }
            """;

    @Test
    void defaultProfileOmitsBareNumber() {
        JsonSchemaImporter.ImportResult imported = new JsonSchemaImporter().importSchema(NUMBER_SCHEMA);
        assertEquals(Optional.empty(), ModelDocument.read(imported.dmJson()).fieldKind("/Root/price"));
    }

    @Test
    void defaultScaleStrategyImportsTheNumber() {
        MappingProfile profile = MappingProfile.defaults()
                .withNumberScale(MappingProfile.NumberScale.DEFAULT_SCALE, 2);
        JsonSchemaImporter.ImportResult imported = new JsonSchemaImporter().importSchema(NUMBER_SCHEMA, profile);

        assertKernelAccepts(imported.dmJson());
        ModelDocument doc = ModelDocument.read(imported.dmJson());
        assertEquals(Optional.of("NUMBER"), doc.fieldKind("/Root/price"), "DEFAULT_SCALE imports the number");
        assertEquals(2, doc.fieldConfig("/Root/price").orElseThrow().findValue("maxFractionalDigits").asInt());
    }

    @Test
    void confirmStrategyImportsBooleanAsConfirm() {
        JsonSchemaImporter.ImportResult dflt = new JsonSchemaImporter().importSchema(BOOL_SCHEMA);
        assertEquals(Optional.of("BOOLEAN"), ModelDocument.read(dflt.dmJson()).fieldKind("/Root/agreed"));

        MappingProfile profile = MappingProfile.defaults().withBooleanKind(MappingProfile.BooleanKind.CONFIRM);
        JsonSchemaImporter.ImportResult confirm = new JsonSchemaImporter().importSchema(BOOL_SCHEMA, profile);
        assertKernelAccepts(confirm.dmJson());
        assertEquals(Optional.of("CONFIRM"), ModelDocument.read(confirm.dmJson()).fieldKind("/Root/agreed"));
    }
}
