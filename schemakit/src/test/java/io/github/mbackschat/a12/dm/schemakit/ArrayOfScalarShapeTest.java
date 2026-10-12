package io.github.mbackschat.a12.dm.schemakit;

import io.github.mbackschat.a12.dm.modelkit.ModelDocument;
import io.github.mbackschat.a12.dm.modelkit.Requiredness;
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
    void defaultProfileImportsAScalarArrayAsRepeatableSingle() {
        JsonSchemaImporter.ImportResult r = new JsonSchemaImporter().importSchema(STRING_ARRAY);
        assertKernelAccepts(r.dmJson());
        ModelDocument doc = ModelDocument.read(r.dmJson());
        assertTrue(doc.groupPaths().contains("/Root/tags"), "best-effort default imports the scalar array");
        assertEquals(Optional.of("STRING"), doc.fieldKind("/Root/tags/value"));
    }

    @Test
    void strictProfileOmitsAScalarArray() {
        JsonSchemaImporter.ImportResult r = new JsonSchemaImporter().importSchema(STRING_ARRAY, MappingProfile.strict());
        assertFalse(ModelDocument.read(r.dmJson()).groupPaths().contains("/Root/tags"), "--strict omits it + to-do");
        assertTrue(omitted(r, "/Root/tags"));
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
    void valueListImportsANonemptyStringArrayAsAStringMultiSelect() {
        MappingProfile p = MappingProfile.defaults().withArrayOfScalar(MappingProfile.ArrayOfScalar.VALUE_LIST);
        JsonSchemaImporter.ImportResult r = new JsonSchemaImporter().importSchema(
                STRING_ARRAY.replace("\"type\": \"string\"", "\"type\": \"string\", \"minLength\": 1"), p);

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
    void aScalarArrayWithoutMaxItemsUsesTheDefaultRepeatCap() {
        // best-effort: an unbounded scalar array still imports (capped at the default), so structure isn't lost
        JsonSchemaImporter.ImportResult r = new JsonSchemaImporter().importSchema(STRING_ARRAY_NO_MAX);
        assertKernelAccepts(r.dmJson());
        ModelDocument doc = ModelDocument.read(r.dmJson());
        assertTrue(doc.groupPaths().contains("/Root/tags"));
        assertEquals(1000, doc.maxRepetitions("/Root/tags"), "the default repeat cap bounds it");
    }

    @Test
    void anUnboundedScalarArrayIsOmittedWithNoCap() {
        MappingProfile p = MappingProfile.defaults().withDefaultRepeatCap(0);
        JsonSchemaImporter.ImportResult r = new JsonSchemaImporter().importSchema(STRING_ARRAY_NO_MAX, p);

        assertFalse(ModelDocument.read(r.dmJson()).groupPaths().contains("/Root/tags"),
                "no maxItems and --default-repeat-cap 0 — an a12 repeatable group needs a bounded max, so it is omitted");
        assertTrue(omitted(r, "/Root/tags"));
        assertKernelAccepts(r.dmJson());
    }

    @Test
    void enumOnlyItemsHaveTheSameEmptyAdmissionAsExplicitStringItems() {
        for (boolean typed : List.of(false, true)) {
            for (boolean emptyAllowed : List.of(false, true)) {
                String items = "{" + (typed ? "\"type\":\"string\"," : "")
                        + "\"enum\":[" + (emptyAllowed ? "\"\"," : "") + "\"express\",\"ground\"]}";
                String schema = "{\"type\":\"object\",\"properties\":{\"tags\":{\"type\":\"array\",\"maxItems\":4,\"items\":" + items + "}}}";
                for (var strategy : List.of(MappingProfile.ArrayOfScalar.REPEATABLE_SINGLE,
                        MappingProfile.ArrayOfScalar.MULTI_SELECT)) {
                    var imported = new JsonSchemaImporter().importSchema(schema,
                            MappingProfile.defaults().withArrayOfScalar(strategy));
                    ModelDocument model = ModelDocument.read(imported.dmJson());
                    String cell = typed + "/" + emptyAllowed + "/" + strategy;
                    assertKernelAccepts(imported.dmJson());
                    boolean isOmitted = emptyAllowed && strategy == MappingProfile.ArrayOfScalar.MULTI_SELECT;
                    assertEquals(isOmitted, omitted(imported, "/Root/tags"), cell);
                    assertEquals(!isOmitted, model.groupPaths().contains("/Root/tags"), cell);
                    if (!isOmitted) {
                        assertEquals(Optional.of(emptyAllowed ? Requiredness.OPTIONAL : Requiredness.REQUIRED),
                                model.requiredness("/Root/tags/value"), cell);
                        assertEquals(emptyAllowed ? List.of("", "express", "ground") : List.of("express", "ground"),
                                model.enumValues("/Root/tags/value"), cell);
                    }
                }
            }
        }
    }

    @Test
    void valueListPreservesStringLengthAndReportsAClosedDomainInsteadOfErasingIt() {
        var profile = MappingProfile.defaults().withArrayOfScalar(MappingProfile.ArrayOfScalar.VALUE_LIST);
        for (int minimum : List.of(1, 2)) {
            var imported = new JsonSchemaImporter().importSchema(
                    STRING_ARRAY.replace("\"type\": \"string\"", "\"type\": \"string\", \"minLength\": " + minimum), profile);
            assertKernelAccepts(imported.dmJson());
            var model = ModelDocument.read(imported.dmJson());
            assertEquals(java.util.OptionalInt.of(minimum), model.fieldMinLength("/Root/tags/value"));
            assertFalse(omitted(imported, "/Root/tags"));
        }
        var domain = new JsonSchemaImporter().importSchema(
                STRING_ARRAY.replace("\"type\": \"string\"", "\"type\": \"string\", \"enum\": [\"express\", \"ground\"]"), profile);
        assertKernelAccepts(domain.dmJson());
        assertFalse(ModelDocument.read(domain.dmJson()).groupPaths().contains("/Root/tags"));
        assertTrue(omitted(domain, "/Root/tags"));
        assertTrue(domain.report().diagnostics().stream().anyMatch(d -> d.contains("enum") && d.contains("MULTI_SELECT")));
    }

    private static boolean omitted(JsonSchemaImporter.ImportResult r, String source) {
        return r.report().dispositions().stream()
                .anyMatch(d -> d.source().equals(source) && d.tier() == TranscodeReport.Tier.OMITTED);
    }
    @Test
    void unresolvedEmptyAdmissionIsOmittedAndExplicitExclusionRetainsTheCarrier() {
        for (String constraint : java.util.List.of("\"pattern\":\"^$\"", "\"format\":\"date\"")) {
            String schema = "{\"type\":\"object\",\"required\":[\"tags\"],\"properties\":{\"tags\":{\"type\":\"array\",\"maxItems\":4,"
                    + "\"items\":{\"type\":\"string\"," + constraint + "},\"contains\":{\"pattern\":\"\"}}}}";
            var imported = new JsonSchemaImporter().importSchema(schema);
            assertKernelAccepts(imported.dmJson());
            assertTrue(ModelDocument.read(imported.dmJson()).fieldPaths().isEmpty());
            assertTrue(imported.report().diagnostics().stream().anyMatch(d -> d.contains("empty admission")));
            assertTrue(imported.report().dispositions().stream().noneMatch(d -> d.tier() == TranscodeReport.Tier.TIER_A_PLUS));
        }
        var excluded = new JsonSchemaImporter().importSchema("{\"type\":\"object\",\"properties\":{\"tags\":{\"type\":\"array\",\"maxItems\":4,"
                + "\"items\":{\"type\":\"string\",\"pattern\":\"^$\",\"minLength\":1}}}}");
        assertKernelAccepts(excluded.dmJson());
        assertEquals(java.util.List.of("/Root/tags/value"), ModelDocument.read(excluded.dmJson()).fieldPaths());
        assertTrue(excluded.report().dispositions().stream().noneMatch(d -> d.tier() == TranscodeReport.Tier.OMITTED));
    }

}
