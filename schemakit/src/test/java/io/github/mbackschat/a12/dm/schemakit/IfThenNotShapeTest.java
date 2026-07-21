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
 * A non-tractable shape (an arbitrary conditional schema, {@code not {type}}, or nested/compound {@code not}) is
 * still <b>reported</b>, never silently dropped (SCHEMAKIT-GAPS).
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
    void multiPropertyIfConjoinsTheConditions() {
        JsonSchemaImporter.ImportResult r = new JsonSchemaImporter().importSchema("""
                { "title": "Sub", "type": "object",
                  "properties": { "tier": { "type": "string" }, "region": { "type": "string" },
                                  "vatId": { "type": "string" } },
                  "if": { "properties": { "tier": { "const": "BUSINESS" }, "region": { "const": "EU" } } },
                  "then": { "required": ["vatId"] } }
                """);

        assertKernelAccepts(r.dmJson());
        assertEquals(1, synthesized(r), () -> "if tier=BUSINESS AND region=EU then vatId required → one rule: " + r.report().dispositions());
        assertTrue(r.dmJson().contains("BUSINESS") && r.dmJson().contains("EU"), "the rule keys on BOTH if-conditions");
    }

    @Test
    void thenValueConstraintSynthesizesAConditionalConstraintRule() {
        JsonSchemaImporter.ImportResult r = new JsonSchemaImporter().importSchema("""
                { "title": "Sub", "type": "object",
                  "properties": { "tier": { "type": "string" }, "discount": { "type": "integer" } },
                  "if": { "properties": { "tier": { "const": "PREMIUM" } } },
                  "then": { "properties": { "discount": { "minimum": 10 } } } }
                """);

        assertKernelAccepts(r.dmJson());
        assertEquals(1, synthesized(r), () -> "if tier=PREMIUM then discount>=10 → one rule: " + r.report().dispositions());
        assertTrue(r.dmJson().contains("PREMIUM"), "the rule keys on the if-condition");
    }

    @Test
    void elseValueConstraintSynthesizesAConditionalRule() {
        JsonSchemaImporter.ImportResult r = new JsonSchemaImporter().importSchema("""
                { "title": "Sub", "type": "object",
                  "properties": { "tier": { "type": "string" }, "discount": { "type": "integer" } },
                  "if": { "properties": { "tier": { "const": "PREMIUM" } } },
                  "else": { "properties": { "discount": { "maximum": 0 } } } }
                """);

        assertKernelAccepts(r.dmJson());
        assertEquals(1, synthesized(r), () -> "if tier≠PREMIUM then discount≤0 → one rule: " + r.report().dispositions());
        assertTrue(r.dmJson().contains("PREMIUM"), "the rule keys on the negated if-condition");
    }

    @Test
    void multiPropertyElseValueConstraintSynthesizesADisjunctiveRule() {
        JsonSchemaImporter.ImportResult r = new JsonSchemaImporter().importSchema("""
                { "title": "Sub", "type": "object",
                  "properties": { "tier": { "type": "string" }, "region": { "type": "string" }, "fee": { "type": "integer" } },
                  "if": { "properties": { "tier": { "const": "PREMIUM" }, "region": { "const": "EU" } } },
                  "else": { "properties": { "fee": { "minimum": 1 } } } }
                """);

        assertKernelAccepts(r.dmJson());
        assertEquals(1, synthesized(r), () -> "else of a multi-property if (a disjunction) → one rule: " + r.report().dispositions());
        assertTrue(r.dmJson().contains("Or"), "the else condition is the disjunction of the negated if-conditions");
    }

    @Test
    void thenPatternConstraintSynthesizesAViolationRule() {
        JsonSchemaImporter.ImportResult r = new JsonSchemaImporter().importSchema("""
                { "title": "Sub", "type": "object",
                  "properties": { "tier": { "type": "string" }, "code": { "type": "string" } },
                  "if": { "properties": { "tier": { "const": "PREMIUM" } } },
                  "then": { "properties": { "code": { "pattern": "^P" } } } }
                """);

        assertKernelAccepts(r.dmJson());
        assertEquals(1, synthesized(r), () -> "if tier=PREMIUM then code matches ^P → one rule: " + r.report().dispositions());
        assertTrue(r.dmJson().contains("PatternViolated"), "the rule fires when code does NOT match (PatternViolated)");
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
    void notMinLengthSynthesizesAnInvertedLengthRule() {
        JsonSchemaImporter.ImportResult r = new JsonSchemaImporter().importSchema("""
                { "title": "X", "type": "object",
                  "properties": { "code": { "type": "string", "not": { "minLength": 3 } } } }
                """);

        assertKernelAccepts(r.dmJson());
        assertEquals(1, synthesized(r), () -> "not minLength → one length rule (length must be < 3): " + r.report().dispositions());
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
    void elseWorksWithANumericDiscriminator() {
        // regression: notEqualsValue must mirror equalsValue on NUMBER, so a numeric discriminator's else maps.
        JsonSchemaImporter.ImportResult r = new JsonSchemaImporter().importSchema("""
                { "title": "Sub", "type": "object",
                  "properties": { "level": { "type": "integer" }, "note": { "type": "string" } },
                  "if": { "properties": { "level": { "const": 5 } } },
                  "then": { "required": [] },
                  "else": { "required": ["note"] } }
                """);

        assertKernelAccepts(r.dmJson());
        assertEquals(1, synthesized(r), () -> "else on a numeric discriminator (level≠5) synthesizes a rule: " + r.report().dispositions());
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
    void objectNotRequiredForbidsTheFieldsBeingPresentTogether() {
        JsonSchemaImporter.ImportResult r = new JsonSchemaImporter().importSchema("""
                { "title": "X", "type": "object",
                  "properties": { "cash": { "type": "string" }, "card": { "type": "string" } },
                  "not": { "required": ["cash", "card"] } }
                """);

        assertKernelAccepts(r.dmJson());
        assertEquals(1, synthesized(r), () -> "not{required:[cash,card]} → one 'not all present' rule: " + r.report().dispositions());
        assertTrue(r.dmJson().contains("AllFieldsFilled"), "the rule fires when both are present");
    }

    @Test
    void aComplexNotIsReportedNotSilentlyDropped() {
        // not{type} is outside the mapped set (const/enum/pattern/min/max/minLength/maxLength) — stays reported.
        JsonSchemaImporter.ImportResult r = new JsonSchemaImporter().importSchema("""
                { "title": "X", "type": "object",
                  "properties": { "f": { "type": "string", "not": { "type": "number" } } } }
                """);

        assertKernelAccepts(r.dmJson());
        assertEquals(0, synthesized(r), "a 'not' outside the mapped set (e.g. not{type}) is not synthesized");
        assertTrue(r.report().diagnostics().stream().anyMatch(d -> d.contains("not")),
                () -> "the unmapped 'not' shape is reported: " + r.report().diagnostics());
    }
}
