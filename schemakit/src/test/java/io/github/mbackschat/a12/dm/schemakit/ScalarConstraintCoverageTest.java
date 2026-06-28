package io.github.mbackschat.a12.dm.schemakit;

import org.junit.jupiter.api.Test;

import static io.github.mbackschat.a12.dm.schemakit.SchemaTestSupport.assertKernelAccepts;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Constraint-coverage matrix (SCHEMAKIT-SPEC §4f): a scalar field kind must NOT silently drop a constraint a
 * sibling kind handles (the omit-never-silent rule, §4a). Found by dogfooding: a {@code type:"number"} field
 * dropped every numeric constraint that {@code type:"integer"} honored (min/max/exclusive/const/multipleOf),
 * and a {@code format}-bearing string dropped {@code const}. This locks the whole class, not just the report.
 */
class ScalarConstraintCoverageTest {

    private static long rules(JsonSchemaImporter.ImportResult r) {
        return r.report().dispositions().stream().filter(d -> d.tier() == TranscodeReport.Tier.TIER_A_PLUS).count();
    }

    private static JsonSchemaImporter.ImportResult importNumberWith(String keyword) {
        return new JsonSchemaImporter().importSchema(
                "{ \"type\":\"object\", \"properties\":{ \"amount\":{ \"type\":\"number\", " + keyword + " } } }");
    }

    @Test
    void numberExclusiveMinimumSynthesizesARule() {
        JsonSchemaImporter.ImportResult r = importNumberWith("\"exclusiveMinimum\": 0");
        assertKernelAccepts(r.dmJson());
        assertEquals(1, rules(r), () -> "number exclusiveMinimum must synthesize a rule: " + r.report().dispositions());
    }

    @Test
    void numberExclusiveMaximumSynthesizesARule() {
        JsonSchemaImporter.ImportResult r = importNumberWith("\"exclusiveMaximum\": 100");
        assertKernelAccepts(r.dmJson());
        assertEquals(1, rules(r), () -> "number exclusiveMaximum must synthesize a rule: " + r.report().dispositions());
    }

    @Test
    void numberConstSynthesizesARule() {
        JsonSchemaImporter.ImportResult r = importNumberWith("\"const\": 42");
        assertKernelAccepts(r.dmJson());
        assertEquals(1, rules(r), () -> "number const must synthesize a rule: " + r.report().dispositions());
    }

    @Test
    void numberMultipleOfSynthesizesARule() {
        JsonSchemaImporter.ImportResult r = importNumberWith("\"multipleOf\": 5");
        assertKernelAccepts(r.dmJson());
        assertEquals(1, rules(r), () -> "number multipleOf must synthesize a rule: " + r.report().dispositions());
    }

    @Test
    void numberInclusiveBoundsBecomeFieldConfig() {
        JsonSchemaImporter.ImportResult r = importNumberWith("\"minimum\": 1, \"maximum\": 9");
        assertKernelAccepts(r.dmJson());
        assertTrue(r.dmJson().contains("minValue"), () -> "number minimum must become a field bound: " + r.dmJson());
        assertTrue(r.dmJson().contains("maxValue"), () -> "number maximum must become a field bound: " + r.dmJson());
    }

    @Test
    void aFormattedStringConstSynthesizesARule() {
        // a string with BOTH format and const must still synthesize the const rule (the format path dropped it).
        JsonSchemaImporter.ImportResult r = new JsonSchemaImporter().importSchema("""
                { "type":"object", "properties":{
                    "channel":{ "type":"string", "format":"email", "const":"ops@example.com" } } }
                """);
        assertKernelAccepts(r.dmJson());
        assertEquals(1, rules(r), () -> "formatted-string const must synthesize a rule: " + r.report().dispositions());
    }

    @Test
    void anArrayOfNumbersHonorsItemExclusiveMinimum() {
        // the scalar-array row field flows through the same addScalar path → its constraints must not drop either.
        JsonSchemaImporter.ImportResult r = new JsonSchemaImporter().importSchema("""
                { "type":"object", "properties":{
                    "scores":{ "type":"array", "maxItems":10,
                      "items":{ "type":"number", "exclusiveMinimum":0 } } } }
                """);
        assertKernelAccepts(r.dmJson());
        assertEquals(1, rules(r), () -> "array-of-number item exclusiveMinimum must synthesize a rule: " + r.report().dispositions());
    }
}
