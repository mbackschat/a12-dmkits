package io.github.mbackschat.a12.dm.schemakit;

import io.github.mbackschat.a12.dm.modelkit.ModelDocument;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.OptionalInt;

import static io.github.mbackschat.a12.dm.schemakit.SchemaTestSupport.assertKernelAccepts;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The MappingProfile (SCHEMAKIT-SPEC §4b) makes choice-driven import decisions overridable. The default is
 * <b>best-effort</b> (import + flag the guess); {@link MappingProfile#strict()} restores omit-everything; a
 * custom profile changes a single decision. Verified here for number-scale and boolean kind.
 */
class MappingProfileShapeTest {

    private static final String NUMBER_SCHEMA = """
            { "type": "object", "properties": { "price": { "type": "number" } } }
            """;
    private static final String BOOL_SCHEMA = """
            { "type": "object", "properties": { "agreed": { "type": "boolean" } } }
            """;

    @Test
    void defaultProfileImportsBareNumberAtScaleTwo() {
        JsonSchemaImporter.ImportResult imported = new JsonSchemaImporter().importSchema(NUMBER_SCHEMA);
        assertKernelAccepts(imported.dmJson());
        ModelDocument doc = ModelDocument.read(imported.dmJson());
        assertEquals(Optional.of("NUMBER"), doc.fieldKind("/Root/price"), "best-effort default imports the number");
        assertEquals(OptionalInt.of(2), doc.fieldMaxFractionalDigits("/Root/price"),
                "at the default scale 2 (the guess is flagged in the report)");
    }

    @Test
    void strictProfileOmitsBareNumber() {
        JsonSchemaImporter.ImportResult imported =
                new JsonSchemaImporter().importSchema(NUMBER_SCHEMA, MappingProfile.strict());
        assertEquals(Optional.empty(), ModelDocument.read(imported.dmJson()).fieldKind("/Root/price"),
                "--strict omits the guessed-scale number");
        assertKernelAccepts(imported.dmJson());
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
