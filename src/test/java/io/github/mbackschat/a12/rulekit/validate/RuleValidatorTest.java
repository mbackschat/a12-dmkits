package io.github.mbackschat.a12.rulekit.validate;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.rulekit.Fixtures;
import io.github.mbackschat.a12.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.rulekit.dsl.Condition;
import io.github.mbackschat.a12.rulekit.dsl.DslRenderer;

/**
 * F4 ground-truth <em>mechanics</em> (proposal §11): rulekit injects a rendered rule into the model
 * as DM-JSON, runs the real kernel's {@code checkConsistency} (no compile, no execute), and reports
 * problems. This file covers the injection/round-trip itself; the per-family kernel-law locks
 * (executable KERNEL-FINDINGS entries) live in {@code validate.laws.*}.
 */
class RuleValidatorTest {

    private static final String modelJson = Fixtures.subscriptionJson();

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
    void validatesRendererOutputEndToEnd() {
        BoundModel m = Fixtures.subscription();
        Condition condition = m.num("/Subscription/Addons/MonthlyFee").filled()
                .and(m.num("/Subscription/Addons/MonthlyFee").le(0));
        String dsl = new DslRenderer().render(condition);

        DraftRule rule = new DraftRule(
                "/Subscription/Addons", "RenderedFeeRule", "../MonthlyFee", dsl, "FEE_POSITIVE", "ERROR");

        ValidationResult result = new RuleValidator().check(modelJson, rule);

        assertTrue(result.ok(), () -> "renderer output should validate against the kernel, got: " + result.problems());
    }

    @Test
    void validatesEnumComparisonAgainstTheKernel() {
        BoundModel m = Fixtures.subscription();
        // Tier is a text-bearing enum (BASIC/PRO/ENTERPRISE) — comparison by stored value is accepted.
        String dsl = new DslRenderer().render(m.enumOf("/Subscription/Tier").is("PRO"));

        DraftRule rule = new DraftRule(
                "/Subscription", "TierIsProRule", "../Tier", dsl, "TIER_PRO", "ERROR");

        ValidationResult result = new RuleValidator().check(modelJson, rule);

        assertTrue(result.ok(), () -> "enum-by-value comparison should validate, got: " + result.problems());
    }
}
