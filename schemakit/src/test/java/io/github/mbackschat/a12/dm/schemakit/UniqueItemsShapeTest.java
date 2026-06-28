package io.github.mbackschat.a12.dm.schemakit;

import org.junit.jupiter.api.Test;

import static io.github.mbackschat.a12.dm.schemakit.SchemaTestSupport.assertKernelAccepts;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code uniqueItems: true} on an array → a synthesized a12 uniqueness rule (SCHEMAKIT-SPEC §4): the
 * repeatable group's row fields become the composite key of a {@code RepetitionNotUnique} rule, scoped to the
 * parent group with the error field inside the row. a12 HAS this operator, so under best-effort it is mapped,
 * not omitted.
 */
class UniqueItemsShapeTest {

    private static final String SCALAR_UNIQUE = """
            { "title": "Tags", "type": "object", "properties": {
                "rows": { "type": "array", "maxItems": 5, "uniqueItems": true,
                          "items": { "type": "string" } } } }
            """;
    private static final String OBJECT_UNIQUE = """
            { "title": "Order", "type": "object", "properties": {
                "lines": { "type": "array", "maxItems": 10, "uniqueItems": true,
                           "items": { "type": "object",
                                      "properties": { "sku": { "type": "string" }, "qty": { "type": "integer" } } } } } }
            """;

    @Test
    void uniqueItemsOnAScalarArraySynthesizesARepetitionNotUniqueRule() {
        JsonSchemaImporter.ImportResult r = new JsonSchemaImporter().importSchema(SCALAR_UNIQUE);

        assertKernelAccepts(r.dmJson());
        assertTrue(r.report().dispositions().stream()
                        .anyMatch(d -> d.tier() == TranscodeReport.Tier.TIER_A_PLUS && d.source().equals("/Root/rows")),
                () -> "uniqueItems → a synthesized rule: " + r.report().dispositions());
        assertTrue(r.dmJson().contains("RepetitionNotUnique"), "the rule uses the a12 uniqueness operator");
    }

    @Test
    void uniqueItemsOnAnObjectArrayKeysOnTheRowFields() {
        JsonSchemaImporter.ImportResult r = new JsonSchemaImporter().importSchema(OBJECT_UNIQUE);

        assertKernelAccepts(r.dmJson());
        assertTrue(r.dmJson().contains("RepetitionNotUnique"), "a composite-key uniqueness rule is synthesized");
        assertTrue(r.report().dispositions().stream()
                        .anyMatch(d -> d.tier() == TranscodeReport.Tier.TIER_A_PLUS && d.source().equals("/Root/lines")),
                () -> "the rule is reported as a Tier-A+ synthesis: " + r.report().dispositions());
    }

    @Test
    void uniqueItemsIsNoLongerReportedAsUnsupported() {
        JsonSchemaImporter.ImportResult r = new JsonSchemaImporter().importSchema(SCALAR_UNIQUE);
        assertTrue(r.report().diagnostics().stream().noneMatch(d -> d.contains("'uniqueItems'") && d.contains("not yet")),
                () -> "uniqueItems is now mapped, not reported as unsupported: " + r.report().diagnostics());
    }
}
