package io.github.mbackschat.a12.dm.schemakit;

import io.github.mbackschat.a12.dm.modelkit.ModelDocument;
import org.junit.jupiter.api.Test;

import static io.github.mbackschat.a12.dm.schemakit.SchemaTestSupport.assertKernelAccepts;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The validation keywords the importer still does not map ({@code contains}/{@code minContains}) must be
 * <b>reported</b>, not silently dropped (the omit-never-silent directive, task #13). The declared structure
 * still imports; the unenforced keyword rides a corrective to-do. ({@code uniqueItems}, {@code if}/{@code then},
 * and {@code not} are no longer here — they now synthesize rules; only the starred-aggregate {@code contains}
 * family remains deferred.)
 */
class UnsupportedKeywordReportTest {

    @Test
    void containsIsReportedAndTheStructureStillImports() {
        JsonSchemaImporter.ImportResult r = new JsonSchemaImporter().importSchema("""
                { "type": "object", "properties": {
                    "tags": { "type": "array", "maxItems": 5, "contains": { "const": "vip" },
                              "items": { "type": "object", "properties": { "v": { "type": "string" } } } } } }
                """);

        assertKernelAccepts(r.dmJson());
        assertTrue(ModelDocument.read(r.dmJson()).isRepeating("/Root/tags"), "the array structure still imports");
        assertTrue(r.report().diagnostics().stream().anyMatch(d -> d.contains("'contains'")),
                () -> "the unmapped contains is reported: " + r.report().diagnostics());
    }
}
