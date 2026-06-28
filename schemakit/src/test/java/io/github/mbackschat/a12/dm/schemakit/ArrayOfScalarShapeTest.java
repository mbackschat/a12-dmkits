package io.github.mbackschat.a12.dm.schemakit;

import io.github.mbackschat.a12.dm.modelkit.ModelDocument;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static io.github.mbackschat.a12.dm.schemakit.SchemaTestSupport.assertKernelAccepts;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The array-of-scalar import strategy (SCHEMAKIT-SPEC §4b; task #2). An {@code array} of scalar items has no
 * single a12 home, so the {@link MappingProfile} picks one of three distinct, non-overlapping targets — and
 * the default stays <b>omit, never block</b>:
 * <ul>
 *   <li>{@code MULTI_SELECT} → an a12 multi-select group (closed enum choices) — needs {@code items.enum};</li>
 *   <li>{@code VALUE_LIST} → a multi-select group with a string field (a free-text value list) — needs string items;</li>
 *   <li>{@code REPEATABLE_SINGLE} → a plain repeatable group with one field (the general form, any scalar kind).</li>
 * </ul>
 * All three need a bounded cap ({@code maxItems ≥ 2}, like an array-of-object), since a12 group repeatability
 * is a bounded max ≥ 2; otherwise the array is omitted. The inner field is named {@code value}.
 */
class ArrayOfScalarShapeTest {

    private static final String ENUM_ARRAY = """
            { "type": "object", "properties": {
                "colors": { "type": "array", "maxItems": 5,
                            "items": { "type": "string", "enum": ["RED", "GREEN", "BLUE"] } } } }
            """;
    private static final String STRING_ARRAY = """
            { "type": "object", "properties": {
                "tags": { "type": "array", "maxItems": 10, "items": { "type": "string" } } } }
            """;
    private static final String INT_ARRAY = """
            { "type": "object", "properties": {
                "scores": { "type": "array", "maxItems": 4, "items": { "type": "integer" } } } }
            """;
    private static final String STRING_ARRAY_NO_MAX = """
            { "type": "object", "properties": {
                "tags": { "type": "array", "items": { "type": "string" } } } }
            """;

    @Test
    void defaultProfileOmitsAScalarArray() {
        JsonSchemaImporter.ImportResult r = new JsonSchemaImporter().importSchema(STRING_ARRAY);
        assertFalse(ModelDocument.read(r.dmJson()).groupPaths().contains("/Root/tags"), "default OMIT: no group");
        assertTrue(omitted(r, "/Root/tags"), "default OMIT records a corrective to-do");
        assertKernelAccepts(r.dmJson());
    }

    @Test
    void multiSelectImportsAnEnumArrayAsAMultiSelectGroup() {
        MappingProfile p = MappingProfile.defaults().withArrayOfScalar(MappingProfile.ArrayOfScalar.MULTI_SELECT);
        JsonSchemaImporter.ImportResult r = new JsonSchemaImporter().importSchema(ENUM_ARRAY, p);

        assertKernelAccepts(r.dmJson());
        ModelDocument doc = ModelDocument.read(r.dmJson());
        assertTrue(doc.groupPaths().contains("/Root/colors"), "a repeatable group is created");
        assertTrue(doc.isRepeating("/Root/colors"));
        assertEquals(5, doc.maxRepetitions("/Root/colors"), "maxItems becomes the repeatability cap");
        assertEquals(Optional.of("ENUMERATION"), doc.fieldKind("/Root/colors/value"));
        assertEquals(List.of("RED", "GREEN", "BLUE"), doc.enumValues("/Root/colors/value"));
    }

    @Test
    void multiSelectWithoutAClosedSetIsOmitted() {
        MappingProfile p = MappingProfile.defaults().withArrayOfScalar(MappingProfile.ArrayOfScalar.MULTI_SELECT);
        JsonSchemaImporter.ImportResult r = new JsonSchemaImporter().importSchema(STRING_ARRAY, p);

        assertFalse(ModelDocument.read(r.dmJson()).groupPaths().contains("/Root/tags"),
                "MULTI_SELECT needs items.enum — string items are omitted, not blocked");
        assertTrue(omitted(r, "/Root/tags"));
        assertKernelAccepts(r.dmJson());
    }

    @Test
    void valueListImportsAStringArrayAsAStringMultiSelect() {
        MappingProfile p = MappingProfile.defaults().withArrayOfScalar(MappingProfile.ArrayOfScalar.VALUE_LIST);
        JsonSchemaImporter.ImportResult r = new JsonSchemaImporter().importSchema(STRING_ARRAY, p);

        assertKernelAccepts(r.dmJson());
        ModelDocument doc = ModelDocument.read(r.dmJson());
        assertTrue(doc.groupPaths().contains("/Root/tags"));
        assertTrue(doc.isRepeating("/Root/tags"));
        assertEquals(Optional.of("STRING"), doc.fieldKind("/Root/tags/value"));
    }

    @Test
    void repeatableSingleImportsAnIntegerArrayAsAPlainRepeatableGroup() {
        MappingProfile p = MappingProfile.defaults().withArrayOfScalar(MappingProfile.ArrayOfScalar.REPEATABLE_SINGLE);
        JsonSchemaImporter.ImportResult r = new JsonSchemaImporter().importSchema(INT_ARRAY, p);

        assertKernelAccepts(r.dmJson());
        ModelDocument doc = ModelDocument.read(r.dmJson());
        assertTrue(doc.groupPaths().contains("/Root/scores"));
        assertTrue(doc.isRepeating("/Root/scores"));
        assertEquals(4, doc.maxRepetitions("/Root/scores"));
        assertEquals(Optional.of("NUMBER"), doc.fieldKind("/Root/scores/value"));
    }

    @Test
    void aScalarArrayWithoutMaxItemsIsOmittedEvenWithAStrategy() {
        MappingProfile p = MappingProfile.defaults().withArrayOfScalar(MappingProfile.ArrayOfScalar.REPEATABLE_SINGLE);
        JsonSchemaImporter.ImportResult r = new JsonSchemaImporter().importSchema(STRING_ARRAY_NO_MAX, p);

        assertFalse(ModelDocument.read(r.dmJson()).groupPaths().contains("/Root/tags"),
                "no maxItems ≥ 2 — an a12 repeatable group needs a bounded max, so it is omitted");
        assertTrue(omitted(r, "/Root/tags"));
        assertKernelAccepts(r.dmJson());
    }

    private static boolean omitted(JsonSchemaImporter.ImportResult r, String source) {
        return r.report().dispositions().stream()
                .anyMatch(d -> d.source().equals(source) && d.tier() == TranscodeReport.Tier.OMITTED);
    }
}
