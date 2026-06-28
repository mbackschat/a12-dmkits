package io.github.mbackschat.a12.dm.schemakit.spike;

import io.github.mbackschat.a12.dm.modelkit.ModelDocument;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static io.github.mbackschat.a12.dm.schemakit.spike.SchemaTestSupport.assertKernelAccepts;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Validation keywords the importer does not yet map ({@code uniqueItems}, {@code contains}/{@code
 * minContains}, {@code if}/{@code then}/{@code else}, {@code not}) must be <b>reported</b>, not silently
 * dropped (the omit-never-silent directive, task #13). The declared structure still imports; the unenforced
 * keyword rides a corrective to-do in the report.
 */
class UnsupportedKeywordReportTest {

    @Test
    void unsupportedValidationKeywordsAreReportedAndStructureStillImports() {
        JsonSchemaImporter.ImportResult r = new JsonSchemaImporter().importSchema("""
                { "type": "object", "properties": {
                    "tags": { "type": "array", "uniqueItems": true, "maxItems": 5,
                              "items": { "type": "object", "properties": { "v": { "type": "string" } } } },
                    "name": { "type": "string", "not": { "const": "anonymous" } } } }
                """);

        assertKernelAccepts(r.dmJson());
        // the structure imports despite the unmapped keywords
        assertEquals(Optional.of("STRING"), ModelDocument.read(r.dmJson()).fieldKind("/Root/name"));
        assertTrue(ModelDocument.read(r.dmJson()).isRepeating("/Root/tags"));
        // both unmapped keywords are reported, not dropped silently
        assertTrue(r.report().diagnostics().stream().anyMatch(d -> d.contains("uniqueItems")), "uniqueItems reported");
        assertTrue(r.report().diagnostics().stream().anyMatch(d -> d.contains("'not'")), "not reported");
    }
}
