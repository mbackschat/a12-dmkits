package io.github.mbackschat.a12.dm.schemakit.spike;

import io.github.mbackschat.a12.dm.modelkit.ModelDocument;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static io.github.mbackschat.a12.dm.schemakit.spike.SchemaTestSupport.assertKernelAccepts;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The map import strategy (SCHEMAKIT-SPEC §4b; task #3). A JSON object used as a <b>map</b> —
 * {@code additionalProperties} (a value schema) or {@code patternProperties} — has no fixed property set, so
 * the default {@code REPEATABLE_KEY_VALUE} strategy turns it into an a12 repeatable group of {@code {key,
 * value}} rows (each row = one entry). Like every repeatable group it needs a bounded cap, taken from
 * {@code maxProperties} (≥ 2); without one it is omitted, never blocked. The key field is {@code key}, the
 * scalar value field {@code value}; an object value's fields are inlined alongside {@code key}.
 */
class MapShapeTest {

    private static final String STRING_MAP = """
            { "type": "object", "properties": {
                "attributes": { "type": "object", "maxProperties": 20,
                                "additionalProperties": { "type": "string" } } } }
            """;
    private static final String INT_MAP = """
            { "type": "object", "properties": {
                "counts": { "type": "object", "maxProperties": 50,
                            "additionalProperties": { "type": "integer" } } } }
            """;
    private static final String PATTERN_MAP = """
            { "type": "object", "properties": {
                "headers": { "type": "object", "maxProperties": 8,
                             "patternProperties": { "^x-": { "type": "string" } } } } }
            """;
    private static final String OBJECT_MAP = """
            { "type": "object", "properties": {
                "items": { "type": "object", "maxProperties": 30,
                           "additionalProperties": { "type": "object",
                               "properties": { "sku": { "type": "string" }, "qty": { "type": "integer" } } } } } }
            """;
    private static final String MAP_NO_CAP = """
            { "type": "object", "properties": {
                "attributes": { "type": "object", "additionalProperties": { "type": "string" } } } }
            """;

    @Test
    void defaultStrategyImportsAStringMapAsAKeyValueGroup() {
        JsonSchemaImporter.ImportResult r = new JsonSchemaImporter().importSchema(STRING_MAP);

        assertKernelAccepts(r.dmJson());
        ModelDocument doc = ModelDocument.read(r.dmJson());
        assertTrue(doc.groupPaths().contains("/Root/attributes"));
        assertTrue(doc.isRepeating("/Root/attributes"));
        assertEquals(20, doc.maxRepetitions("/Root/attributes"), "maxProperties becomes the cap");
        assertEquals(Optional.of("STRING"), doc.fieldKind("/Root/attributes/key"));
        assertEquals(Optional.of("STRING"), doc.fieldKind("/Root/attributes/value"));
    }

    @Test
    void aMapOfIntegerValuesGivesANumberValueField() {
        JsonSchemaImporter.ImportResult r = new JsonSchemaImporter().importSchema(INT_MAP);

        assertKernelAccepts(r.dmJson());
        ModelDocument doc = ModelDocument.read(r.dmJson());
        assertEquals(Optional.of("STRING"), doc.fieldKind("/Root/counts/key"));
        assertEquals(Optional.of("NUMBER"), doc.fieldKind("/Root/counts/value"));
    }

    @Test
    void patternPropertiesAreAlsoAMap() {
        JsonSchemaImporter.ImportResult r = new JsonSchemaImporter().importSchema(PATTERN_MAP);

        assertKernelAccepts(r.dmJson());
        ModelDocument doc = ModelDocument.read(r.dmJson());
        assertTrue(doc.groupPaths().contains("/Root/headers"));
        assertEquals(Optional.of("STRING"), doc.fieldKind("/Root/headers/key"));
        assertEquals(Optional.of("STRING"), doc.fieldKind("/Root/headers/value"));
    }

    @Test
    void aMapOfObjectsInlinesTheValueFieldsAlongsideKey() {
        JsonSchemaImporter.ImportResult r = new JsonSchemaImporter().importSchema(OBJECT_MAP);

        assertKernelAccepts(r.dmJson());
        ModelDocument doc = ModelDocument.read(r.dmJson());
        assertTrue(doc.isRepeating("/Root/items"));
        assertEquals(Optional.of("STRING"), doc.fieldKind("/Root/items/key"));
        assertEquals(Optional.of("STRING"), doc.fieldKind("/Root/items/sku"));
        assertEquals(Optional.of("NUMBER"), doc.fieldKind("/Root/items/qty"));
    }

    @Test
    void aMapWithoutMaxPropertiesIsOmitted() {
        JsonSchemaImporter.ImportResult r = new JsonSchemaImporter().importSchema(MAP_NO_CAP);

        assertFalse(ModelDocument.read(r.dmJson()).groupPaths().contains("/Root/attributes"),
                "no maxProperties ≥ 2 — an a12 repeatable group needs a bounded max, so it is omitted");
        assertTrue(omitted(r, "/Root/attributes"));
        assertKernelAccepts(r.dmJson());
    }

    @Test
    void theOmitStrategySkipsTheMapWithATodo() {
        MappingProfile p = MappingProfile.defaults().withMapStrategy(MappingProfile.MapStrategy.OMIT);
        JsonSchemaImporter.ImportResult r = new JsonSchemaImporter().importSchema(STRING_MAP, p);

        assertFalse(ModelDocument.read(r.dmJson()).groupPaths().contains("/Root/attributes"));
        assertTrue(omitted(r, "/Root/attributes"));
        assertKernelAccepts(r.dmJson());
    }

    private static boolean omitted(JsonSchemaImporter.ImportResult r, String source) {
        return r.report().dispositions().stream()
                .anyMatch(d -> d.source().equals(source) && d.tier() == TranscodeReport.Tier.OMITTED);
    }
}
