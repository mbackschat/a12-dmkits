package io.github.mbackschat.a12.dm.rulekit.validate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.Fixtures;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer;

/**
 * F4 ground-truth <em>mechanics</em> (PROJECT-DESIGN §11): rulekit injects a rendered rule into the model
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

        assertTrue(result.ok(), () -> "expected a valid rule, got: " + result.diagnostics());
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

        assertTrue(result.ok(), () -> "renderer output should validate against the kernel, got: " + result.diagnostics());
    }

    @Test
    void rejectsADuplicateNameInsteadOfThrowing() {
        // A rule whose name collides with an existing sibling (the MonthlyFee field) makes the kernel throw
        // IllegalArgumentException ("two child elements with the same name") during conversion — inside
        // checkConsistency, before it can report a problem. check() must catch the collision up front and
        // return a clean RK_NAME_EXISTS rejection, never let the exception escape as a raw stack trace.
        DraftRule rule = new DraftRule(
                "/Subscription/Addons", "MonthlyFee", "../MonthlyFee",
                "FieldFilled(MonthlyFee)", "DUP", "ERROR");

        ValidationResult result = new RuleValidator().check(modelJson, rule);  // must NOT throw

        assertFalse(result.ok(), "a name collision is rejected");
        assertTrue(result.diagnostics().stream()
                        .anyMatch(d -> d.code().filter("RK_NAME_EXISTS"::equals).isPresent()),
                () -> "carries RK_NAME_EXISTS, got: " + result.diagnostics());
    }

    @Test
    void rejectsAMissingGroupInsteadOfThrowing() {
        // Adding to a non-existent group made inject() throw IllegalArgumentException("no group at path") —
        // another escape of the same class. check() must return a clean RK_NO_SUCH_GROUP instead.
        DraftRule rule = new DraftRule(
                "/Subscription/DoesNotExist", "SomeRule", "../X", "FieldFilled(X)", "X", "ERROR");

        ValidationResult result = new RuleValidator().check(modelJson, rule);  // must NOT throw

        assertFalse(result.ok(), "a missing target group is rejected");
        assertTrue(result.diagnostics().stream()
                        .anyMatch(d -> d.code().filter("RK_NO_SUCH_GROUP"::equals).isPresent()),
                () -> "carries RK_NO_SUCH_GROUP, got: " + result.diagnostics());
    }

    @Test
    void validatesEnumComparisonAgainstTheKernel() {
        BoundModel m = Fixtures.subscription();
        // Tier is a text-bearing enum (BASIC/PRO/ENTERPRISE) — comparison by stored value is accepted.
        String dsl = new DslRenderer().render(m.enumOf("/Subscription/Tier").is("PRO"));

        DraftRule rule = new DraftRule(
                "/Subscription", "TierIsProRule", "../Tier", dsl, "TIER_PRO", "ERROR");

        ValidationResult result = new RuleValidator().check(modelJson, rule);

        assertTrue(result.ok(), () -> "enum-by-value comparison should validate, got: " + result.diagnostics());
    }
}
