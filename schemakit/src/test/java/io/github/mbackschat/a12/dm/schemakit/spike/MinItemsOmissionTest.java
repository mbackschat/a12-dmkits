package io.github.mbackschat.a12.dm.schemakit.spike;

import io.github.mbackschat.a12.dm.modelkit.ModelDocument;
import org.junit.jupiter.api.Test;

import static io.github.mbackschat.a12.dm.schemakit.spike.SchemaTestSupport.assertKernelAccepts;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code minItems} (a minimum group-row count) has no clean a12 rule form (the error entity controls a
 * rule's iteration; {@code NumberOfFilledGroups} needs ≥ 2 group args — KERNEL-FINDINGS), so it is reported
 * as <b>not enforced</b>, never silently promised as a rule (task #7). The array's structure still imports.
 */
class MinItemsOmissionTest {

    private static final String ARRAY_WITH_MIN = """
            { "type": "object", "properties": {
                "lines": { "type": "array", "minItems": 1, "maxItems": 50,
                           "items": { "type": "object", "properties": { "sku": { "type": "string" } } } } } }
            """;

    @Test
    void minItemsIsReportedAsUnenforcedAndSynthesizesNoRule() {
        JsonSchemaImporter.ImportResult r = new JsonSchemaImporter().importSchema(ARRAY_WITH_MIN);

        assertKernelAccepts(r.dmJson());
        assertTrue(ModelDocument.read(r.dmJson()).isRepeating("/Root/lines"), "the repeatable group still imports");
        assertTrue(r.report().diagnostics().stream().anyMatch(d -> d.contains("minItems") && d.contains("NOT enforced")),
                "minItems is honestly reported as not enforced, not promised as a rule");
        assertEquals(0, r.report().dispositions().stream()
                .filter(d -> d.tier() == TranscodeReport.Tier.TIER_A_PLUS).count(),
                "no rule is synthesized for minItems");
    }
}
