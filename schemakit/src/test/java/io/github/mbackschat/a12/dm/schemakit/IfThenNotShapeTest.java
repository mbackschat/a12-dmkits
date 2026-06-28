package io.github.mbackschat.a12.dm.schemakit;

import org.junit.jupiter.api.Test;

import static io.github.mbackschat.a12.dm.schemakit.SchemaTestSupport.assertKernelAccepts;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The tractable cases of {@code if}/{@code then} and {@code not} (SCHEMAKIT-SPEC §4) — a12 has the operators,
 * so under best-effort the common shapes synthesize rules instead of being reported as omitted:
 * <ul>
 *   <li>{@code if: {prop = value}, then: {required:[…]}} → a conditional-requiredness rule per dependent;</li>
 *   <li>{@code not: {const}} → the field must not equal the value; {@code not: {enum}} → not one of the values.</li>
 * </ul>
 * A non-tractable shape (arbitrary {@code if}/{@code then} schema, complex {@code not}) is still <b>reported</b>,
 * never silently dropped.
 */
class IfThenNotShapeTest {

    private static long synthesized(JsonSchemaImporter.ImportResult r) {
        return r.report().dispositions().stream().filter(d -> d.tier() == TranscodeReport.Tier.TIER_A_PLUS).count();
    }

    @Test
    void ifThenRequiredSynthesizesAConditionalRequirednessRule() {
        JsonSchemaImporter.ImportResult r = new JsonSchemaImporter().importSchema("""
                { "title": "Sub", "type": "object",
                  "properties": { "tier": { "type": "string" }, "discount": { "type": "string" } },
                  "if": { "properties": { "tier": { "const": "PREMIUM" } } },
                  "then": { "required": ["discount"] } }
                """);

        assertKernelAccepts(r.dmJson());
        assertEquals(1, synthesized(r), () -> "if tier=PREMIUM then discount required → one rule: " + r.report().dispositions());
        assertTrue(r.dmJson().contains("PREMIUM"), "the rule keys on the if-condition value");
    }

    @Test
    void ifThenWorksWithAnEnumDiscriminator() {
        JsonSchemaImporter.ImportResult r = new JsonSchemaImporter().importSchema("""
                { "title": "Sub", "type": "object",
                  "properties": { "kind": { "type": "string", "enum": ["A", "B"] }, "extra": { "type": "string" } },
                  "if": { "properties": { "kind": { "const": "B" } } },
                  "then": { "required": ["extra"] } }
                """);

        assertKernelAccepts(r.dmJson());
        assertEquals(1, synthesized(r), () -> "enum discriminator works too: " + r.report().dispositions());
    }

    @Test
    void notConstSynthesizesAnInequalityRule() {
        JsonSchemaImporter.ImportResult r = new JsonSchemaImporter().importSchema("""
                { "title": "X", "type": "object",
                  "properties": { "status": { "type": "string", "not": { "const": "BANNED" } } } }
                """);

        assertKernelAccepts(r.dmJson());
        assertEquals(1, synthesized(r), () -> "not const → one rule: " + r.report().dispositions());
        assertTrue(r.dmJson().contains("BANNED"), "the rule forbids the value");
    }

    @Test
    void notEnumSynthesizesANotInListRule() {
        JsonSchemaImporter.ImportResult r = new JsonSchemaImporter().importSchema("""
                { "title": "X", "type": "object",
                  "properties": { "color": { "type": "string", "not": { "enum": ["NONE", "UNKNOWN"] } } } }
                """);

        assertKernelAccepts(r.dmJson());
        assertEquals(1, synthesized(r), () -> "not enum → one rule: " + r.report().dispositions());
        assertTrue(r.dmJson().contains("FieldValueIncludedInValueList"), "the rule fires when the value IS in the list");
    }

    @Test
    void aComplexNotIsReportedNotSilentlyDropped() {
        JsonSchemaImporter.ImportResult r = new JsonSchemaImporter().importSchema("""
                { "title": "X", "type": "object",
                  "properties": { "f": { "type": "string", "not": { "pattern": "^x" } } } }
                """);

        assertKernelAccepts(r.dmJson());
        assertEquals(0, synthesized(r), "a non-const/enum 'not' is not synthesized");
        assertTrue(r.report().diagnostics().stream().anyMatch(d -> d.contains("not")),
                () -> "the unmapped 'not' shape is reported: " + r.report().diagnostics());
    }
}
