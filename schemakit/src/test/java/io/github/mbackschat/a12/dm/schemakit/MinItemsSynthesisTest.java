package io.github.mbackschat.a12.dm.schemakit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.mbackschat.a12.dm.adapter.KernelAdapter;
import io.github.mbackschat.a12.dm.adapter.ReadResult;
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer;
import org.junit.jupiter.api.Test;

import static io.github.mbackschat.a12.dm.schemakit.SchemaTestSupport.assertKernelAccepts;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code minItems} (a minimum group-row count) is now SYNTHESIZED as a {@code NumberOfFilledGroups(G*) < N}
 * rule (KERNEL-FINDINGS §9 "group cardinality", corrected 2026-07-09; the 2026-06-28 impossibility was
 * overturned — {@code RootGroupCountLawsTest} / {@code NumberOfFilledGroupsStarDiffTest}). The error is
 * anchored IN-GROUP and UNGUARDED (SCHEMAKIT-SPEC §4f), so it fires at 0…N-1 rows unconditionally — a guarded
 * outside anchoring would be suppressed when its guard field is empty (a minItems false-negative). The
 * synthesized rule projects back to a native {@code minItems} on export (Tier A), so the shape round-trips.
 * {@code maxItems} stays structural (repeatability).
 */
class MinItemsSynthesisTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String ARRAY_WITH_MIN = """
            { "type": "object", "properties": {
                "lines": { "type": "array", "minItems": 2, "maxItems": 50,
                           "items": { "type": "object", "properties": { "sku": { "type": "string" } } } } } }
            """;

    @Test
    void minItemsSynthesizesAKernelAcceptedRule() {
        JsonSchemaImporter.ImportResult r = new JsonSchemaImporter().importSchema(ARRAY_WITH_MIN);

        assertKernelAccepts(r.dmJson());
        assertEquals(1, r.report().dispositions().stream()
                        .filter(d -> d.tier() == TranscodeReport.Tier.TIER_A_PLUS
                                && d.target().contains("minItems")).count(),
                "minItems is synthesized as a Tier A+ rule");

        // the synthesized rule is the in-group, unguarded NumberOfFilledGroups(G*) < N shape
        boolean shape = KernelAdapter.ofJson(r.dmJson()).readAll().values().stream()
                .filter(v -> v instanceof ReadResult.Converted)
                .map(v -> new DslRenderer().render(((ReadResult.Converted) v).condition()))
                .anyMatch(txt -> txt.contains("NumberOfFilledGroups") && txt.contains("lines*") && txt.contains("< 2"));
        assertTrue(shape, "expected a NumberOfFilledGroups(lines*) < 2 rule");
    }

    @Test
    void synthesizedRuleProjectsBackToMinItemsOnExport() throws Exception {
        String dm = new JsonSchemaImporter().importSchema(ARRAY_WITH_MIN).dmJson();
        JsonSchemaExporter.ExportResult exported = new JsonSchemaExporter().export(dm);
        JsonNode schema = MAPPER.readTree(exported.jsonSchema());

        assertEquals(2, schema.path("properties").path("lines").path("minItems").asInt(),
                "the synthesized rule must project back to a native minItems keyword");
        assertTrue(exported.report().dispositions().stream()
                        .anyMatch(d -> d.tier() == TranscodeReport.Tier.TIER_A && d.target().contains("minItems")),
                "the projection is recorded as a Tier-A disposition");
    }

    @Test
    void importExportImportIsStable() {
        String dm1 = new JsonSchemaImporter().importSchema(ARRAY_WITH_MIN).dmJson();
        String schema = new JsonSchemaExporter().export(dm1).jsonSchema();
        JsonSchemaImporter.ImportResult back = new JsonSchemaImporter().importSchema(schema);
        assertKernelAccepts(back.dmJson());
        // re-importing the exported minItems re-synthesizes the same cardinality rule
        boolean shape = KernelAdapter.ofJson(back.dmJson()).readAll().values().stream()
                .filter(v -> v instanceof ReadResult.Converted)
                .map(v -> new DslRenderer().render(((ReadResult.Converted) v).condition()))
                .anyMatch(txt -> txt.contains("NumberOfFilledGroups") && txt.contains("< 2"));
        assertTrue(shape, "minItems must survive schema → DM → schema → DM");
    }

    @Test
    void minItemsZeroIsANoOp() {
        String zero = ARRAY_WITH_MIN.replace("\"minItems\": 2", "\"minItems\": 0");
        JsonSchemaImporter.ImportResult r = new JsonSchemaImporter().importSchema(zero);
        assertEquals(0, r.report().dispositions().stream()
                        .filter(d -> d.tier() == TranscodeReport.Tier.TIER_A_PLUS
                                && d.target().contains("minItems")).count(),
                "minItems: 0 is always satisfied — no rule");
    }
}
