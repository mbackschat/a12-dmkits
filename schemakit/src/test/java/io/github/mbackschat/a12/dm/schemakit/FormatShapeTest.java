package io.github.mbackschat.a12.dm.schemakit;

import io.github.mbackschat.a12.dm.modelkit.ModelDocument;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static io.github.mbackschat.a12.dm.schemakit.SchemaTestSupport.assertKernelAccepts;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The string {@code format} strategy (SCHEMAKIT-SPEC §4b; task #6). Temporal formats map to a12's native
 * temporal field <b>kinds</b> ({@code date}→DATE, {@code date-time}→DATE_TIME, {@code time}→TIME) — always,
 * since those are first-class a12 types, strictly better than a string. The semantic string formats
 * (email/uuid/…) follow the {@code MappingProfile.format} strategy: {@code PATTERN} (default) adds a standard
 * regex where one is known; {@code CUSTOM_TYPE} emits a custom field type named after the format;
 * {@code IGNORE} drops the format. An unknown format under PATTERN is a plain string + a to-do, never a block.
 */
class FormatShapeTest {

    private static String stringWithFormat(String format) {
        return """
                { "type": "object", "properties": { "f": { "type": "string", "format": "%s" } } }
                """.formatted(format);
    }

    @Test
    void temporalFormatsBecomeNativeTemporalFieldKinds() {
        ModelDocument date = importDoc(stringWithFormat("date"));
        assertEquals(Optional.of("DATE"), date.fieldKind("/Root/f"));

        ModelDocument dateTime = importDoc(stringWithFormat("date-time"));
        assertEquals(Optional.of("DATE_TIME"), dateTime.fieldKind("/Root/f"));

        ModelDocument time = importDoc(stringWithFormat("time"));
        assertEquals(Optional.of("TIME"), time.fieldKind("/Root/f"));
    }

    @Test
    void emailAndUuidBecomeStringsWithAStandardPattern() {
        JsonSchemaImporter.ImportResult email = new JsonSchemaImporter().importSchema(stringWithFormat("email"));
        assertKernelAccepts(email.dmJson());
        ModelDocument emailDoc = ModelDocument.read(email.dmJson());
        assertEquals(Optional.of("STRING"), emailDoc.fieldKind("/Root/f"));
        assertTrue(emailDoc.fieldPattern("/Root/f").isPresent(), "email → a standard regex");

        ModelDocument uuidDoc = importDoc(stringWithFormat("uuid"));
        assertEquals(Optional.of("STRING"), uuidDoc.fieldKind("/Root/f"));
        assertTrue(uuidDoc.fieldPattern("/Root/f").isPresent(), "uuid → a standard regex");
    }

    @Test
    void anUnknownFormatUnderPatternIsAPlainStringWithATodo() {
        JsonSchemaImporter.ImportResult r = new JsonSchemaImporter().importSchema(stringWithFormat("uri"));
        assertKernelAccepts(r.dmJson());
        ModelDocument doc = ModelDocument.read(r.dmJson());
        assertEquals(Optional.of("STRING"), doc.fieldKind("/Root/f"));
        assertTrue(doc.fieldPattern("/Root/f").isEmpty(), "no standard regex for uri");
        assertTrue(r.report().diagnostics().stream().anyMatch(d -> d.contains("uri")), "the gap is reported");
    }

    @Test
    void theIgnoreStrategyDropsTheFormat() {
        MappingProfile p = MappingProfile.defaults().withFormat(MappingProfile.FormatStrategy.IGNORE);
        JsonSchemaImporter.ImportResult r = new JsonSchemaImporter().importSchema(stringWithFormat("email"), p);
        assertKernelAccepts(r.dmJson());
        ModelDocument doc = ModelDocument.read(r.dmJson());
        assertEquals(Optional.of("STRING"), doc.fieldKind("/Root/f"));
        assertTrue(doc.fieldPattern("/Root/f").isEmpty(), "IGNORE drops the format → plain string");
    }

    @Test
    void theCustomTypeStrategyEmitsACustomField() {
        MappingProfile p = MappingProfile.defaults().withFormat(MappingProfile.FormatStrategy.CUSTOM_TYPE);
        JsonSchemaImporter.ImportResult r = new JsonSchemaImporter().importSchema(stringWithFormat("email"), p);
        assertKernelAccepts(r.dmJson());
        assertEquals(Optional.of("CUSTOM"), ModelDocument.read(r.dmJson()).fieldKind("/Root/f"));
    }

    private static ModelDocument importDoc(String schema) {
        JsonSchemaImporter.ImportResult r = new JsonSchemaImporter().importSchema(schema);
        assertKernelAccepts(r.dmJson());
        return ModelDocument.read(r.dmJson());
    }
}
