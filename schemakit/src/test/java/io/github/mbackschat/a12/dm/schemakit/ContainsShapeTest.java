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
    void rangeContainsClauseSynthesizesACountRule() {
        JsonSchemaImporter.ImportResult r = new JsonSchemaImporter().importSchema("""
                { "type": "object", "properties": {
                    "scores": { "type": "array", "maxItems": 10,
                      "items": { "type": "object", "properties": { "value": { "type": "integer" } } },
                      "contains": { "properties": { "value": { "minimum": 80 } } }, "minContains": 1 } } }
                """);

        assertKernelAccepts(r.dmJson());
        assertEquals(1, synthesized(r), () -> "≥1 score with value ≥ 80 → one count rule (range clause): " + r.report().dispositions());
    }

    @Test
    void aContainsWithANonValueClauseIsReported() {
        // a clause with no value constraint (just a type) has no positive match condition → reported.
        JsonSchemaImporter.ImportResult r = new JsonSchemaImporter().importSchema("""
                { "type": "object", "properties": {
                    "items": { "type": "array", "maxItems": 10,
                      "items": { "type": "object", "properties": { "a": { "type": "string" } } },
                      "contains": { "properties": { "a": { "type": "string" } } } } } }
                """);

        assertKernelAccepts(r.dmJson());
        assertEquals(0, synthesized(r), "a contains clause with no value constraint isn't mapped");
        assertTrue(r.report().diagnostics().stream().anyMatch(d -> d.contains("contains")),
                () -> "the unmapped contains is reported: " + r.report().diagnostics());
    }

    @Test
    void aNestedObjectContainsClauseSynthesizesACountRule() {
        // the contains predicate descends one level into a nested object (a non-repeatable subgroup of the row):
        // "≥1 order whose payment.method == CARD" → a Having-filtered count over /orders*/payment/method.
        JsonSchemaImporter.ImportResult r = new JsonSchemaImporter().importSchema("""
                { "type": "object", "properties": {
                    "orders": { "type": "array", "maxItems": 50,
                      "items": { "type": "object", "properties": {
                        "ref": { "type": "string" },
                        "payment": { "type": "object", "properties": { "method": { "type": "string" } } } } },
                      "contains": { "properties": { "payment": { "properties": { "method": { "const": "CARD" } } } } },
                      "minContains": 1 } } }
                """);

        assertKernelAccepts(r.dmJson());
        assertEquals(1, synthesized(r), () -> "≥1 order with payment.method=CARD → one count rule: " + r.report().dispositions());
        assertTrue(r.dmJson().contains("CARD"), "the rule keys on the nested predicate value");
    }

    @Test
    void aDeeplyNestedObjectContainsClauseSynthesizesACountRule() {
        // two levels deep: "≥1 shipment whose origin.address.country == DE".
        JsonSchemaImporter.ImportResult r = new JsonSchemaImporter().importSchema("""
                { "type": "object", "properties": {
                    "shipments": { "type": "array", "maxItems": 50,
                      "items": { "type": "object", "properties": {
                        "origin": { "type": "object", "properties": {
                          "address": { "type": "object", "properties": { "country": { "type": "string" } } } } } } },
                      "contains": { "properties": { "origin": { "properties": {
                        "address": { "properties": { "country": { "const": "DE" } } } } } } },
                      "minContains": 1 } } }
                """);

        assertKernelAccepts(r.dmJson());
        assertEquals(1, synthesized(r), () -> "≥1 shipment with origin.address.country=DE → one rule: " + r.report().dispositions());
        assertTrue(r.dmJson().contains("DE"), "the rule keys on the two-level-nested predicate value");
    }

    @Test
    void aNestedArrayContainsClauseStaysReported() {
        // descending into a nested *array* (a deeper repetition) is NOT attempted — that is nested aggregation,
        // which stays a declined gap (SCHEMAKIT-GAPS-LEDGER §3). The structure imports; the predicate is reported.
        JsonSchemaImporter.ImportResult r = new JsonSchemaImporter().importSchema("""
                { "type": "object", "properties": {
                    "orders": { "type": "array", "maxItems": 50,
                      "items": { "type": "object", "properties": {
                        "lines": { "type": "array", "maxItems": 10,
                          "items": { "type": "object", "properties": { "sku": { "type": "string" } } } } } },
                      "contains": { "properties": { "lines": { "type": "array" } } }, "minContains": 1 } } }
                """);

        assertKernelAccepts(r.dmJson());
        assertEquals(0, synthesized(r), "a contains clause that descends into a nested array isn't mapped");
        assertTrue(r.report().diagnostics().stream().anyMatch(d -> d.contains("contains")),
                () -> "the nested-array contains is reported: " + r.report().diagnostics());
    }
}
