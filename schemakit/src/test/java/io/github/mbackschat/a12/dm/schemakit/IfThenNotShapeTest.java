package io.github.mbackschat.a12.dm.schemakit;

import org.junit.jupiter.api.Test;

import static io.github.mbackschat.a12.dm.schemakit.SchemaTestSupport.assertKernelAccepts;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The tractable cases of {@code if}/{@code then}/{@code else} and {@code not} (SCHEMAKIT-SPEC §4) — a12 has the
 * operators, so under best-effort the common shapes synthesize rules instead of being reported as omitted:
 * <ul>
 *   <li>{@code if: {prop = value}, then: {required:[…]}} → a conditional-requiredness rule per dependent;</li>
 *   <li>{@code else: {required:[…]}} → the same, fired on the <em>negated</em> condition (field present, value differs);</li>
 *   <li>{@code not: {const}} → must not equal; {@code not: {enum}} → not one of; {@code not: {pattern}} → must not
 *       match; {@code not: {minimum/maximum}} → the inverted numeric bound.</li>
 * </ul>
 * A non-tractable shape (arbitrary {@code if}/{@code then}/{@code else} value-constraints, complex {@code not} such
 * as {@code minLength}/{@code type}/nested) is still <b>reported</b>, never silently dropped (SCHEMAKIT-GAPS-LEDGER).
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
    void ifThenElseSynthesizesBothBranches() {
        JsonSchemaImporter.ImportResult r = new JsonSchemaImporter().importSchema("""
                { "title": "Sub", "type": "object",
                  "properties": { "tier": { "type": "string" },
                                  "discount": { "type": "string" }, "reason": { "type": "string" } },
                  "if": { "properties": { "tier": { "const": "PREMIUM" } } },
                  "then": { "required": ["discount"] },
                  "else": { "required": ["reason"] } }
                """);

        assertKernelAccepts(r.dmJson());
        assertEquals(2, synthesized(r), () -> "then (discount) + else (reason) → two rules: " + r.report().dispositions());
        assertTrue(r.dmJson().contains("discount") && r.dmJson().contains("reason"), "both branches reference their field");
    }

    @Test
    void elseWorksWithAnEnumDiscriminator() {
        JsonSchemaImporter.ImportResult r = new JsonSchemaImporter().importSchema("""
                { "title": "Sub", "type": "object",
                  "properties": { "kind": { "type": "string", "enum": ["A", "B"] }, "extra": { "type": "string" } },
                  "if": { "properties": { "kind": { "const": "A" } } },
                  "then": { "required": [] },
                  "else": { "required": ["extra"] } }
                """);

        assertKernelAccepts(r.dmJson());
        assertEquals(1, synthesized(r), () -> "else on an enum discriminator synthesizes a rule: " + r.report().dispositions());
    }

    @Test
    void notPatternSynthesizesAMatchRule() {
        JsonSchemaImporter.ImportResult r = new JsonSchemaImporter().importSchema("""
                { "title": "X", "type": "object",
                  "properties": { "code": { "type": "string", "not": { "pattern": "^TMP" } } } }
                """);

        assertKernelAccepts(r.dmJson());
        assertEquals(1, synthesized(r), () -> "not pattern → one rule: " + r.report().dispositions());
        assertTrue(r.dmJson().contains("TMP"), "the rule fires when the value matches the forbidden pattern");
    }

    @Test
    void notMinimumSynthesizesAnInvertedBoundRule() {
        JsonSchemaImporter.ImportResult r = new JsonSchemaImporter().importSchema("""
                { "title": "X", "type": "object",
                  "properties": { "qty": { "type": "integer", "not": { "minimum": 100 } } } }
                """);

        assertKernelAccepts(r.dmJson());
        assertEquals(1, synthesized(r), () -> "not minimum → one rule (value must be < 100): " + r.report().dispositions());
    }

    @Test
    void notMaximumSynthesizesAnInvertedBoundRule() {
        JsonSchemaImporter.ImportResult r = new JsonSchemaImporter().importSchema("""
                { "title": "X", "type": "object",
                  "properties": { "qty": { "type": "integer", "not": { "maximum": 10 } } } }
                """);

        assertKernelAccepts(r.dmJson());
        assertEquals(1, synthesized(r), () -> "not maximum → one rule (value must be > 10): " + r.report().dispositions());
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
        // not{minLength} is outside the mapped set (const/enum/pattern/min/max) — it stays reported, not dropped.
        JsonSchemaImporter.ImportResult r = new JsonSchemaImporter().importSchema("""
                { "title": "X", "type": "object",
                  "properties": { "f": { "type": "string", "not": { "minLength": 3 } } } }
                """);

        assertKernelAccepts(r.dmJson());
        assertEquals(0, synthesized(r), "a 'not' outside const/enum/pattern/min/max is not synthesized");
        assertTrue(r.report().diagnostics().stream().anyMatch(d -> d.contains("not")),
                () -> "the unmapped 'not' shape is reported: " + r.report().diagnostics());
    }
}
