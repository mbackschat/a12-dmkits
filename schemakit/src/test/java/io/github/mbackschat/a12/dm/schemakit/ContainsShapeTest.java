package io.github.mbackschat.a12.dm.schemakit;

import org.junit.jupiter.api.Test;

import static io.github.mbackschat.a12.dm.schemakit.SchemaTestSupport.assertKernelAccepts;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code contains}/{@code minContains}/{@code maxContains} (SCHEMAKIT-SPEC §4f): the array must have ≥/≤ N rows
 * matching a supported predicate. Object properties use a disjoint failure count; unsupported empty-value
 * distinctions and compound predicates are explicitly reported.
 */
class ContainsShapeTest {

    private static long synthesized(JsonSchemaImporter.ImportResult r) {
        return r.report().dispositions().stream().filter(d -> d.tier() == TranscodeReport.Tier.TIER_A_PLUS).count();
    }

    @Test
    void anEmptyRejectingObjectPredicateIsExplicitlyOmitted() {
        JsonSchemaImporter.ImportResult r = new JsonSchemaImporter().importSchema("""
                { "type": "object", "required": ["members"], "properties": {
                    "members": { "type": "array", "maxItems": 10,
                      "items": { "type": "object",
                        "properties": { "kind": { "type": "string" }, "name": { "type": "string" } } },
                      "contains": { "properties": { "kind": { "const": "VIP" } } },
                      "minContains": 2 } } }
                """);

        assertKernelAccepts(r.dmJson());
        assertEquals(0, synthesized(r));
        assertTrue(r.report().dispositions().stream().anyMatch(d -> d.tier() == TranscodeReport.Tier.OMITTED));
    }

    @Test
    void containsOnScalarArraySynthesizesAMinCountRule() {
        JsonSchemaImporter.ImportResult r = new JsonSchemaImporter().importSchema("""
                { "type": "object", "required": ["tags"], "properties": {
                    "tags": { "type": "array", "maxItems": 10, "items": { "type": "string" },
                      "contains": { "const": "vip" } } } }
                """);

        assertKernelAccepts(r.dmJson());
        assertEquals(1, synthesized(r), () -> "bare contains = at least one row with value=vip: " + r.report().dispositions());
    }

    @Test
    void minAndMaxContainsSynthesizeTwoRules() {
        JsonSchemaImporter.ImportResult r = new JsonSchemaImporter().importSchema("""
                { "type": "object", "required": ["slots"], "properties": {
                    "slots": { "type": "array", "maxItems": 10,
                      "items": { "type": "object", "properties": { "state": { "type": "string" } } },
                      "contains": { "properties": { "state": { "enum": ["", "OPEN"] } } },
                      "minContains": 1, "maxContains": 3 } } }
                """);

        assertKernelAccepts(r.dmJson());
        assertEquals(2, synthesized(r), () -> "minContains + maxContains → two rules: " + r.report().dispositions());
    }

    @Test
    void anUnsupportedObjectClauseOmitsTheWholeConjunction() {
        JsonSchemaImporter.ImportResult r = new JsonSchemaImporter().importSchema("""
                { "type": "object", "required": ["members"], "properties": {
                    "members": { "type": "array", "maxItems": 10,
                      "items": { "type": "object", "properties": { "kind": { "type": "string" }, "region": { "type": "string" } } },
                      "contains": { "properties": { "kind": { "const": "VIP" }, "region": { "const": "EU" } } },
                      "minContains": 1 } } }
                """);

        assertKernelAccepts(r.dmJson());
        assertEquals(0, synthesized(r));
        assertTrue(r.report().dispositions().stream().anyMatch(d -> d.tier() == TranscodeReport.Tier.OMITTED));
    }

    @Test
    void rangeContainsClauseSynthesizesACountRule() {
        JsonSchemaImporter.ImportResult r = new JsonSchemaImporter().importSchema("""
                { "type": "object", "required": ["scores"], "properties": {
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
                { "type": "object", "required": ["items"], "properties": {
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
        JsonSchemaImporter.ImportResult r = new JsonSchemaImporter().importSchema("""
                { "type": "object", "required": ["orders"], "properties": {
                    "orders": { "type": "array", "maxItems": 50,
                      "items": { "type": "object", "properties": {
                        "ref": { "type": "string" },
                        "payment": { "type": "object", "properties": { "method": { "type": "string" } } } } },
                      "contains": { "properties": { "payment": { "properties": { "method": { "const": "" } } } } },
                      "minContains": 1 } } }
                """);

        assertKernelAccepts(r.dmJson());
        assertEquals(1, synthesized(r));
        assertTrue(r.dmJson().contains("orders*/payment/method"));
    }

    @Test
    void aDeeplyNestedObjectContainsClauseSynthesizesACountRule() {
        JsonSchemaImporter.ImportResult r = new JsonSchemaImporter().importSchema("""
                { "type": "object", "required": ["shipments"], "properties": {
                    "shipments": { "type": "array", "maxItems": 50,
                      "items": { "type": "object", "properties": {
                        "origin": { "type": "object", "properties": {
                          "address": { "type": "object", "properties": { "country": { "type": "string" } } } } } } },
                      "contains": { "properties": { "origin": { "properties": {
                        "address": { "properties": { "country": { "const": "" } } } } } } },
                      "minContains": 1 } } }
                """);

        assertKernelAccepts(r.dmJson());
        assertEquals(1, synthesized(r));
        assertTrue(r.dmJson().contains("shipments*/origin/address/country"));
    }

    @Test
    void aNestedArrayContainsClauseStaysReported() {
        // descending into a nested *array* (a deeper repetition) is NOT attempted — that is nested aggregation,
        // which stays a declined gap (SCHEMAKIT-GAPS §3). The structure imports; the predicate is reported.
        JsonSchemaImporter.ImportResult r = new JsonSchemaImporter().importSchema("""
                { "type": "object", "required": ["orders"], "properties": {
                    "orders": { "type": "array", "maxItems": 50,
                      "items": { "type": "object", "required": ["lines"], "properties": {
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
