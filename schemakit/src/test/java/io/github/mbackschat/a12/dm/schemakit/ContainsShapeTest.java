package io.github.mbackschat.a12.dm.schemakit;

import org.junit.jupiter.api.Test;

import static io.github.mbackschat.a12.dm.schemakit.SchemaTestSupport.assertKernelAccepts;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code contains}/{@code minContains}/{@code maxContains} (SCHEMAKIT-SPEC §4f): the array must have ≥/≤ N rows
 * matching a <em>simple</em> predicate — synthesized as a {@code Having}-filtered {@code NumberOfFilledFields}
 * count over the repeatable group. A non-simple predicate (arbitrary item subschema) stays reported.
 */
class ContainsShapeTest {

    private static long synthesized(JsonSchemaImporter.ImportResult r) {
        return r.report().dispositions().stream().filter(d -> d.tier() == TranscodeReport.Tier.TIER_A_PLUS).count();
    }

    @Test
    void containsOnObjectArraySynthesizesAMinCountRule() {
        JsonSchemaImporter.ImportResult r = new JsonSchemaImporter().importSchema("""
                { "type": "object", "properties": {
                    "members": { "type": "array", "maxItems": 10,
                      "items": { "type": "object",
                        "properties": { "kind": { "type": "string" }, "name": { "type": "string" } } },
                      "contains": { "properties": { "kind": { "const": "VIP" } } },
                      "minContains": 2 } } }
                """);

        assertKernelAccepts(r.dmJson());
        assertEquals(1, synthesized(r), () -> "≥2 members with kind=VIP → one count rule: " + r.report().dispositions());
        assertTrue(r.dmJson().contains("VIP"), "the rule keys on the contains predicate value");
    }

    @Test
    void containsOnScalarArraySynthesizesAMinCountRule() {
        JsonSchemaImporter.ImportResult r = new JsonSchemaImporter().importSchema("""
                { "type": "object", "properties": {
                    "tags": { "type": "array", "maxItems": 10, "items": { "type": "string" },
                      "contains": { "const": "vip" } } } }
                """);

        assertKernelAccepts(r.dmJson());
        assertEquals(1, synthesized(r), () -> "bare contains = at least one row with value=vip: " + r.report().dispositions());
    }

    @Test
    void minAndMaxContainsSynthesizeTwoRules() {
        JsonSchemaImporter.ImportResult r = new JsonSchemaImporter().importSchema("""
                { "type": "object", "properties": {
                    "slots": { "type": "array", "maxItems": 10,
                      "items": { "type": "object", "properties": { "state": { "type": "string" } } },
                      "contains": { "properties": { "state": { "const": "OPEN" } } },
                      "minContains": 1, "maxContains": 3 } } }
                """);

        assertKernelAccepts(r.dmJson());
        assertEquals(2, synthesized(r), () -> "minContains + maxContains → two rules: " + r.report().dispositions());
    }

    @Test
    void multiPropertyContainsSynthesizesACountRule() {
        JsonSchemaImporter.ImportResult r = new JsonSchemaImporter().importSchema("""
                { "type": "object", "properties": {
                    "members": { "type": "array", "maxItems": 10,
                      "items": { "type": "object", "properties": { "kind": { "type": "string" }, "region": { "type": "string" } } },
                      "contains": { "properties": { "kind": { "const": "VIP" }, "region": { "const": "EU" } } },
                      "minContains": 1 } } }
                """);

        assertKernelAccepts(r.dmJson());
        assertEquals(1, synthesized(r), () -> "≥1 member with kind=VIP AND region=EU → one count rule: " + r.report().dispositions());
        assertTrue(r.dmJson().contains("VIP") && r.dmJson().contains("EU"), "both predicate clauses key the Having filter");
    }

    @Test
    void aContainsWithAnUnpinnedPropertyIsReportedNotMapped() {
        // a property that isn't a single value (here a range/type-only) → non-simple predicate → reported.
        JsonSchemaImporter.ImportResult r = new JsonSchemaImporter().importSchema("""
                { "type": "object", "properties": {
                    "items": { "type": "array", "maxItems": 10,
                      "items": { "type": "object", "properties": { "a": { "type": "integer" }, "b": { "type": "string" } } },
                      "contains": { "properties": { "a": { "minimum": 5 }, "b": { "const": "X" } } } } } }
                """);

        assertKernelAccepts(r.dmJson());
        assertEquals(0, synthesized(r), "a contains predicate with a non-single-valued property is not mapped");
        assertTrue(r.report().diagnostics().stream().anyMatch(d -> d.contains("contains")),
                () -> "the unmapped contains is reported: " + r.report().diagnostics());
    }
}
