package io.github.mbackschat.a12.dm.schemakit;

import io.github.mbackschat.a12.dm.modelkit.ModelDocument;
import io.github.mbackschat.a12.dm.modelkit.Requiredness;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static io.github.mbackschat.a12.dm.schemakit.SchemaTestSupport.assertKernelAccepts;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The {@code allOf} merge strategy (SCHEMAKIT-SPEC §4b; task #5). Unlike {@code oneOf}/{@code anyOf} (pick
 * one variant), {@code allOf} means <b>every</b> subschema applies at once — a true intersection. So the
 * object subschemas are <b>merged</b> into one group: the union of all their properties, with {@code
 * required} the union across subschemas (a field required in any subschema is required in the merge). A
 * {@code $ref} subschema (the JSON-Schema "extends a base" pattern) is resolved and merged too.
 */
class AllOfShapeTest {

    private static final String INLINE_ALLOF = """
            { "type": "object", "properties": {
                "profile": { "allOf": [
                    { "type": "object", "required": ["name"], "properties": { "name": { "type": "string" } } },
                    { "type": "object", "properties": { "age": { "type": "integer" } } } ] } } }
            """;
    private static final String REF_BASE_ALLOF = """
            { "type": "object",
              "$defs": { "Base": { "type": "object", "required": ["id"],
                                   "properties": { "id": { "type": "string" } } } },
              "properties": {
                "entity": { "allOf": [
                    { "$ref": "#/$defs/Base" },
                    { "type": "object", "properties": { "label": { "type": "string" } } } ] } } }
            """;
    private static final String OBJECT_PLUS_ALLOF = """
            { "type": "object", "properties": {
                "order": { "type": "object",
                           "properties": { "sku": { "type": "string" } },
                           "allOf": [ { "type": "object", "required": ["qty"],
                                        "properties": { "qty": { "type": "integer" } } } ] } } }
            """;

    @Test
    void allOfMergesInlineObjectSubschemasUnioningPropertiesAndRequired() {
        JsonSchemaImporter.ImportResult r = new JsonSchemaImporter().importSchema(INLINE_ALLOF);

        assertKernelAccepts(r.dmJson());
        ModelDocument doc = ModelDocument.read(r.dmJson());
        assertEquals(Optional.of("STRING"), doc.fieldKind("/Root/profile/name"));
        assertEquals(Optional.of("NUMBER"), doc.fieldKind("/Root/profile/age"));
        assertEquals(Optional.of(Requiredness.REQUIRED), doc.requiredness("/Root/profile/name"),
                "required carries across the merge");
        assertEquals(Optional.of(Requiredness.OPTIONAL), doc.requiredness("/Root/profile/age"));
    }

    @Test
    void allOfResolvesAndMergesARefBase() {
        JsonSchemaImporter.ImportResult r = new JsonSchemaImporter().importSchema(REF_BASE_ALLOF);

        assertKernelAccepts(r.dmJson());
        ModelDocument doc = ModelDocument.read(r.dmJson());
        assertEquals(Optional.of("STRING"), doc.fieldKind("/Root/entity/id"), "the $ref base is merged in");
        assertEquals(Optional.of("STRING"), doc.fieldKind("/Root/entity/label"));
        assertEquals(Optional.of(Requiredness.REQUIRED), doc.requiredness("/Root/entity/id"));
    }

    @Test
    void anObjectWithItsOwnPropertiesPlusAllOfMergesBoth() {
        JsonSchemaImporter.ImportResult r = new JsonSchemaImporter().importSchema(OBJECT_PLUS_ALLOF);

        assertKernelAccepts(r.dmJson());
        ModelDocument doc = ModelDocument.read(r.dmJson());
        assertEquals(Optional.of("STRING"), doc.fieldKind("/Root/order/sku"), "the object's own field");
        assertEquals(Optional.of("NUMBER"), doc.fieldKind("/Root/order/qty"), "the allOf-contributed field");
        assertEquals(Optional.of(Requiredness.REQUIRED), doc.requiredness("/Root/order/qty"));
    }
}
