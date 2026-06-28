package io.github.mbackschat.a12.dm.schemakit;

import io.github.mbackschat.a12.dm.modelkit.ModelDocument;
import io.github.mbackschat.a12.dm.modelkit.Requiredness;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static io.github.mbackschat.a12.dm.schemakit.SchemaTestSupport.assertKernelAccepts;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The residual JSON Schema features with no static a12 form (SCHEMAKIT-SPEC §4c; task #10). Each is made
 * <b>explicit</b> — detected and reported with a corrective to-do, never silently dropped — and the two §4c
 * exceptions that DO map are honoured: a nullable {@code type:[X,"null"]} → an optional {@code X}, and a
 * recursive {@code $ref} expands to the {@code MappingProfile.recursionDepth}. Import never blocks.
 */
class ResidualOmissionsTest {

    private static JsonSchemaImporter.ImportResult imp(String schema) {
        JsonSchemaImporter.ImportResult r = new JsonSchemaImporter().importSchema(schema);
        assertKernelAccepts(r.dmJson());
        return r;
    }

    private static boolean omittedWith(JsonSchemaImporter.ImportResult r, String source, String needle) {
        boolean omitted = r.report().dispositions().stream()
                .anyMatch(d -> d.source().equals(source) && d.tier() == TranscodeReport.Tier.OMITTED);
        boolean reported = r.report().diagnostics().stream().anyMatch(d -> d.contains(needle));
        return omitted && reported;
    }

    @Test
    void nullableTypeMapsToAnOptionalFieldOfThatKind() {
        JsonSchemaImporter.ImportResult r = imp("""
                { "type": "object", "required": ["nick"], "properties": {
                    "nick": { "type": ["string", "null"] } } }
                """);
        ModelDocument doc = ModelDocument.read(r.dmJson());
        assertEquals(Optional.of("STRING"), doc.fieldKind("/Root/nick"), "nullable string → string");
        assertEquals(Optional.of(Requiredness.OPTIONAL), doc.requiredness("/Root/nick"), "nullable ⇒ optional");
    }

    @Test
    void aCrossKindTypeUnionIsOmittedExplicitly() {
        JsonSchemaImporter.ImportResult r = imp("""
                { "type": "object", "properties": { "x": { "type": ["string", "integer"] } } }
                """);
        assertFalse(ModelDocument.read(r.dmJson()).fieldPaths().contains("/Root/x"));
        assertTrue(omittedWith(r, "/Root/x", "cross-kind"), "cross-kind type union is reported");
    }

    @Test
    void aMixedKindOneOfIsOmittedExplicitly() {
        JsonSchemaImporter.ImportResult r = imp("""
                { "type": "object", "properties": {
                    "x": { "oneOf": [ { "type": "string" }, { "type": "integer" } ] } } }
                """);
        assertTrue(omittedWith(r, "/Root/x", "cross-kind"), "a non-object oneOf is a reported cross-kind union");
    }

    @Test
    void dynamicRefIsOmittedExplicitly() {
        JsonSchemaImporter.ImportResult r = imp("""
                { "type": "object", "properties": { "node": { "$dynamicRef": "#node" } } }
                """);
        assertTrue(omittedWith(r, "/Root/node", "$dynamicRef"), "$dynamicRef is reported");
    }

    @Test
    void unevaluatedPropertiesIsReportedButTheDeclaredStructureStillImports() {
        JsonSchemaImporter.ImportResult r = imp("""
                { "type": "object", "properties": {
                    "addr": { "type": "object", "unevaluatedProperties": false,
                              "properties": { "city": { "type": "string" } } } } }
                """);
        assertEquals(Optional.of("STRING"), ModelDocument.read(r.dmJson()).fieldKind("/Root/addr/city"),
                "the declared structure imports");
        assertTrue(r.report().diagnostics().stream().anyMatch(d -> d.contains("unevaluated")),
                "the unenforced unevaluatedProperties is reported");
    }

    private static final String RECURSIVE = """
            { "type": "object",
              "$defs": { "Node": { "type": "object", "properties": {
                            "value": { "type": "string" },
                            "child": { "$ref": "#/$defs/Node" } } } },
              "properties": { "root": { "$ref": "#/$defs/Node" } } }
            """;

    @Test
    void aRecursiveRefIsOmittedByDefault() {
        JsonSchemaImporter.ImportResult r = imp(RECURSIVE);
        ModelDocument doc = ModelDocument.read(r.dmJson());
        assertEquals(Optional.of("STRING"), doc.fieldKind("/Root/root/value"), "one level expands");
        assertFalse(doc.groupPaths().contains("/Root/root/child"), "the recursion is omitted by default");
        assertTrue(r.report().diagnostics().stream().anyMatch(d -> d.contains("recursive")));
    }

    @Test
    void boundedRecursionExpandsToTheConfiguredDepth() {
        MappingProfile p = MappingProfile.defaults().withRecursionDepth(1);
        JsonSchemaImporter.ImportResult r = new JsonSchemaImporter().importSchema(RECURSIVE, p);
        assertKernelAccepts(r.dmJson());
        ModelDocument doc = ModelDocument.read(r.dmJson());
        assertEquals(Optional.of("STRING"), doc.fieldKind("/Root/root/value"));
        assertEquals(Optional.of("STRING"), doc.fieldKind("/Root/root/child/value"), "one extra level expands");
        assertFalse(doc.groupPaths().contains("/Root/root/child/child"), "but no deeper than recursionDepth");
    }
}
