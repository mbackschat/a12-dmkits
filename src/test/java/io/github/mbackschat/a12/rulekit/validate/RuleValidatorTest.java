package io.github.mbackschat.a12.rulekit.validate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.rulekit.dsl.Condition;
import io.github.mbackschat.a12.rulekit.dsl.DslRenderer;
import io.github.mbackschat.a12.rulekit.model.ModelIndex;
import io.github.mbackschat.a12.rulekit.model.ModelLoader;

/**
 * F4 ground-truth check (proposal §11): rulekit injects a rendered rule into the model as DM-JSON,
 * runs the real kernel's {@code checkConsistency} (no compile, no execute), and reports problems.
 * The validator operates on the rule's {@code errorCondition} text + context, independent of how the
 * text was produced.
 */
class RuleValidatorTest {

    private static String modelJson;

    @BeforeAll
    static void loadFixture() throws Exception {
        try (InputStream in = RuleValidatorTest.class.getClassLoader()
                .getResourceAsStream("models/subscription.dm.json")) {
            assertNotNull(in, "fixture not found");
            modelJson = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void acceptsAWellFormedRule() {
        // Mirrors the kernel demo's proven QuantityPositiveRule pattern (short-name form).
        DraftRule rule = new DraftRule(
                "/Subscription/Addons", "FeePositiveRule", "../MonthlyFee",
                "FieldFilled(MonthlyFee) And [MonthlyFee] <= 0", "FEE_POSITIVE", "ERROR");

        ValidationResult result = new RuleValidator().check(modelJson, rule);

        assertTrue(result.ok(), () -> "expected a valid rule, got: " + result.problems());
    }

    @Test
    void validatesRendererOutputEndToEnd() throws Exception {
        BoundModel m;
        try (Reader r = new InputStreamReader(
                RuleValidatorTest.class.getClassLoader().getResourceAsStream("models/subscription.dm.json"),
                StandardCharsets.UTF_8)) {
            m = BoundModel.of(ModelIndex.of(ModelLoader.loadSelfContained(r)));
        }
        Condition condition = m.num("/Subscription/Addons/MonthlyFee").filled()
                .and(m.num("/Subscription/Addons/MonthlyFee").le(0));
        String dsl = new DslRenderer().render(condition);

        DraftRule rule = new DraftRule(
                "/Subscription/Addons", "RenderedFeeRule", "../MonthlyFee", dsl, "FEE_POSITIVE", "ERROR");

        ValidationResult result = new RuleValidator().check(modelJson, rule);

        assertTrue(result.ok(), () -> "renderer output should validate against the kernel, got: " + result.problems());
    }

    @Test
    void validatesEnumComparisonAgainstTheKernel() throws Exception {
        BoundModel m;
        try (Reader r = new InputStreamReader(
                RuleValidatorTest.class.getClassLoader().getResourceAsStream("models/subscription.dm.json"),
                StandardCharsets.UTF_8)) {
            m = BoundModel.of(ModelIndex.of(ModelLoader.loadSelfContained(r)));
        }
        // Tier is a text-bearing enum (BASIC/PRO/ENTERPRISE) — confirm comparison by stored value is accepted.
        String dsl = new DslRenderer().render(m.enumOf("/Subscription/Tier").is("PRO"));

        DraftRule rule = new DraftRule(
                "/Subscription", "TierIsProRule", "../Tier", dsl, "TIER_PRO", "ERROR");

        ValidationResult result = new RuleValidator().check(modelJson, rule);

        assertTrue(result.ok(), () -> "enum-by-value comparison should validate, got: " + result.problems());
    }

    @Test
    void acceptsScaleFreeNumericLiteralsOnEquality() {
        // Verified 2026-06-03: the kernel's decimal-scale check (MVK_INVALID_COMPARE_DEC_PLACES)
        // applies to field-vs-field comparisons only — numeric LITERALS are accepted at any written
        // scale against a scale-2 field (== 0, == 0.00, == 0.0, != 0, <= 0.000 all pass).
        // This test locks that behavior so a kernel upgrade that changes it fails loudly.
        // (See KERNEL-INTEGRATION-NOTES.md; the field-vs-field rejection is N7-proven via the
        // catalog's Equal invalidExample.)
        for (String cond : new String[]{"[MonthlyFee] == 0", "[MonthlyFee] == 0.0", "[MonthlyFee] != 0"}) {
            DraftRule rule = new DraftRule(
                    "/Subscription/Addons", "LiteralScaleRule", "../MonthlyFee",
                    "FieldFilled(MonthlyFee) And " + cond, "LIT_SCALE", "ERROR");
            ValidationResult result = new RuleValidator().check(modelJson, rule);
            assertTrue(result.ok(), () -> cond + " should be accepted, got: " + result.problems());
        }
    }

    @Test
    void rejectsATypeIllegalComparison() {
        // Ordering a string ([Name]) is a type error — MVK_INVALID_TYPE_FOR_COMPARISON.
        DraftRule rule = new DraftRule(
                "/Subscription/Addons", "BadComparisonRule", "../Name",
                "FieldFilled(Name) And [Name] <= [MonthlyFee]", "BAD_CMP", "ERROR");

        ValidationResult result = new RuleValidator().check(modelJson, rule);

        assertFalse(result.ok(), "string ordering should be rejected");
        assertTrue(
                result.problems().stream()
                        .anyMatch(p -> p.mvkCode().filter("MVK_INVALID_TYPE_FOR_COMPARISON"::equals).isPresent()),
                () -> "expected MVK_INVALID_TYPE_FOR_COMPARISON, got: " + result.problems());
    }
}
