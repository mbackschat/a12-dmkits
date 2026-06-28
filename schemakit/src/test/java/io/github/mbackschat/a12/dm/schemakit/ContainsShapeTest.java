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
    void aNonSimpleContainsIsReportedNotMapped() {
        JsonSchemaImporter.ImportResult r = new JsonSchemaImporter().importSchema("""
                { "type": "object", "properties": {
                    "items": { "type": "array", "maxItems": 10,
                      "items": { "type": "object", "properties": { "a": { "type": "string" }, "b": { "type": "string" } } },
                      "contains": { "properties": { "a": { "type": "string" }, "b": { "const": "X" } } } } } }
                """);

        assertKernelAccepts(r.dmJson());
        assertEquals(0, synthesized(r), "a multi-property contains predicate is not mapped");
        assertTrue(r.report().diagnostics().stream().anyMatch(d -> d.contains("contains")),
                () -> "the unmapped contains is reported: " + r.report().diagnostics());
    }
}
