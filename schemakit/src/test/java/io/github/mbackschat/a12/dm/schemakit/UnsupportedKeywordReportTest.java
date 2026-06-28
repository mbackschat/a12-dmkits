package io.github.mbackschat.a12.dm.schemakit;

import io.github.mbackschat.a12.dm.modelkit.ModelDocument;
import org.junit.jupiter.api.Test;

import static io.github.mbackschat.a12.dm.schemakit.SchemaTestSupport.assertKernelAccepts;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A keyword the importer can't map for a given shape must be <b>reported</b>, not silently dropped (the
 * omit-never-silent directive). The declared structure still imports; the unenforced keyword rides a corrective
 * to-do. Most former gaps now synthesize rules (uniqueItems, if/then/else, not, then-constraints, and a
 * <em>simple</em> {@code contains}); this guards the residual reporting path — here a {@code contains {const}}
 * on an array of <em>objects</em> (a const can't match an object → a non-simple predicate → reported).
 */
class UnsupportedKeywordReportTest {

    @Test
    void aNonSimpleContainsIsReportedAndTheStructureStillImports() {
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
