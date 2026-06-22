package io.github.mbackschat.a12.dm.rulekit.lint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.mgmtp.a12.model.notification.Severity;

import io.github.mbackschat.a12.dm.Fixtures;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.Rule;
import io.github.mbackschat.a12.dm.rulekit.validate.Diagnostic;
import io.github.mbackschat.a12.dm.rulekit.validate.DiagnosticSource;

/**
 * F4a lint layer — valid-but-suspicious conditions the kernel accepts but an LLM shouldn't have
 * written. Pure-AST structural heuristics plus the model-context one (an unguarded negative under a
 * repeatable group, which pre-warns the kernel-delegated {@code MVK_NEG_CONDITION_IN_ITERATION}).
 */
class LinterTest {

    private static final BoundModel m = Fixtures.subscription();
    private static final String NAME = "/Subscription/Addons/Name";
    private static final String FEE = "/Subscription/Addons/MonthlyFee";

    private final Linter linter = new Linter();

    @Test
    void flagsAContradictoryPresenceConjunction() {
        // FieldFilled(X) And FieldNotFilled(X) — never satisfiable; the rule can never fire.
        Condition c = m.field(NAME).filled().and(m.field(NAME).notFilled());

        Diagnostic d = only(linter.lint(c), "RK_CONTRADICTORY_PRESENCE");
        assertEquals(DiagnosticSource.LINT, d.source());
        assertEquals(Severity.WARNING, d.severity());
        assertTrue(d.summary().contains(NAME), () -> "summary should name the field: " + d.summary());
        assertTrue(d.fix().isPresent(), "a contradiction lint should suggest dropping one side");
    }

    @Test
    void flagsADuplicateOperand() {
        Condition once = m.field(NAME).filled();
        Condition c = once.and(once);

        Diagnostic d = only(linter.lint(c), "RK_REDUNDANT_OPERAND");
        assertEquals(DiagnosticSource.LINT, d.source());
        assertEquals(Severity.WARNING, d.severity());
    }

    @Test
    void flagsNonComplementaryPresenceDisjunction() {
        // FieldFilled(X) Or FieldNotFilled(X) looks like a tautology but is NOT: a formal-error
        // value satisfies neither (the kernel gotcha — they are not strict complements).
        Condition c = m.field(NAME).filled().or(m.field(NAME).notFilled());

        Diagnostic d = only(linter.lint(c), "RK_PRESENCE_NOT_COMPLEMENTARY");
        assertEquals(DiagnosticSource.LINT, d.source());
        assertTrue(d.explain().isPresent(), "should explain the not-complementary gotcha");
    }

    @Test
    void aCleanConditionHasNoLints() {
        Condition c = m.field(NAME).filled().and(m.num(FEE).gt("100"));
        assertTrue(linter.lint(c).isEmpty(), () -> "unexpected lints: " + linter.lint(c));
    }

    // --- model-context lint: unguarded negative under a repeatable group (pre-warns MVK_NEG_CONDITION_IN_ITERATION)

    @Test
    void warnsOnAnUnguardedNegativeUnderIteration() {
        // Error field under repeatable /Subscription/Addons → the rule iterates; bare FieldNotFilled is unguarded.
        Rule rule = m.rule(m.group("/Subscription/Addons"))
                .name("FeePresentRule")
                .errorField(m.num(FEE))
                .when(m.field(FEE).notFilled())
                .code("FEE_PRESENT").severity(io.github.mbackschat.a12.dm.rulekit.dsl.Severity.ERROR)
                .message(Dsl.en("Fee required."), Dsl.de("Gebühr erforderlich."))
                .build();

        Diagnostic d = only(linter.lint(m, rule), "RK_NEG_CONDITION_IN_ITERATION");
        assertEquals(DiagnosticSource.LINT, d.source());
        assertEquals(Severity.WARNING.name(), d.severity().name());
        assertTrue(d.where().operator().filter("FieldNotFilled"::equals).isPresent());
        assertTrue(d.fix().isPresent(), "should suggest guarding the negative");
    }

    @Test
    void doesNotWarnWhenTheNegativeIsGuarded() {
        Rule rule = m.rule(m.group("/Subscription/Addons"))
                .name("FeeGuardedRule")
                .errorField(m.num(FEE))
                .when(Dsl.groupFilled(m.group("/Subscription/Addons")).and(m.field(FEE).notFilled()))
                .code("FEE_GUARDED").severity(io.github.mbackschat.a12.dm.rulekit.dsl.Severity.ERROR)
                .message(Dsl.en("Fee required."), Dsl.de("Gebühr erforderlich."))
                .build();

        assertTrue(linter.lint(m, rule).stream()
                .noneMatch(d -> d.code().filter("RK_NEG_CONDITION_IN_ITERATION"::equals).isPresent()),
                () -> "guarded negative should not warn: " + linter.lint(m, rule));
    }

    @Test
    void doesNotWarnWhenTheRuleDoesNotIterate() {
        // /Subscription/Tier sits in the non-repeatable root group → the rule does not iterate.
        Rule rule = m.rule(m.group("/Subscription"))
                .name("TierPresentRule")
                .errorField(m.field("/Subscription/Tier"))
                .when(m.field("/Subscription/Tier").notFilled())
                .code("TIER_PRESENT").severity(io.github.mbackschat.a12.dm.rulekit.dsl.Severity.ERROR)
                .message(Dsl.en("Tier required."), Dsl.de("Stufe erforderlich."))
                .build();

        assertTrue(linter.lint(m, rule).stream()
                .noneMatch(d -> d.code().filter("RK_NEG_CONDITION_IN_ITERATION"::equals).isPresent()),
                () -> "non-iterating rule should not warn: " + linter.lint(m, rule));
    }

    // --- model-context lint: inverted requiredness (a bare FieldFilled on the error field)

    @Test
    void warnsWhenABareFieldFilledOnTheErrorFieldInvertsRequiredness() {
        // errorCondition fires on the INVALID case; FieldFilled(Tier) errors when Tier IS present.
        Rule rule = m.rule(m.group("/Subscription"))
                .name("TierRequiredRule")
                .errorField(m.field("/Subscription/Tier"))
                .when(m.field("/Subscription/Tier").filled())
                .code("TIER_REQUIRED").severity(io.github.mbackschat.a12.dm.rulekit.dsl.Severity.ERROR)
                .message(Dsl.en("Tier required."), Dsl.de("Stufe erforderlich."))
                .build();

        Diagnostic d = only(linter.lint(m, rule), "RK_INVERTED_REQUIREDNESS");
        assertEquals(DiagnosticSource.LINT, d.source());
        assertEquals(Severity.WARNING, d.severity());
        assertTrue(d.where().operator().filter("FieldFilled"::equals).isPresent());
        assertTrue(d.fix().filter(f -> f.contains("FieldNotFilled")).isPresent(),
                () -> "fix should propose FieldNotFilled: " + d.fix());
    }

    @Test
    void doesNotWarnOnACorrectRequirednessRule() {
        Rule rule = m.rule(m.group("/Subscription"))
                .name("TierRequiredRule")
                .errorField(m.field("/Subscription/Tier"))
                .when(m.field("/Subscription/Tier").notFilled())
                .code("TIER_REQUIRED").severity(io.github.mbackschat.a12.dm.rulekit.dsl.Severity.ERROR)
                .message(Dsl.en("Tier required."), Dsl.de("Stufe erforderlich."))
                .build();

        assertTrue(linter.lint(m, rule).stream()
                .noneMatch(d -> d.code().filter("RK_INVERTED_REQUIREDNESS"::equals).isPresent()),
                () -> "a FieldNotFilled requiredness rule should not warn: " + linter.lint(m, rule));
    }

    @Test
    void doesNotWarnWhenFieldFilledIsGuarded() {
        // A guarded FieldFilled(E) And <ctx> is a legitimate "field must be empty here" rule — not flagged.
        Rule rule = m.rule(m.group("/Subscription"))
                .name("TierForbiddenRule")
                .errorField(m.field("/Subscription/Tier"))
                .when(m.field("/Subscription/Tier").filled().and(m.field("/Subscription/AutoRenew").filled()))
                .code("TIER_CTX").severity(io.github.mbackschat.a12.dm.rulekit.dsl.Severity.ERROR)
                .message(Dsl.en("Tier check."), Dsl.de("Stufenprüfung."))
                .build();

        assertTrue(linter.lint(m, rule).stream()
                .noneMatch(d -> d.code().filter("RK_INVERTED_REQUIREDNESS"::equals).isPresent()),
                () -> "a guarded FieldFilled should not warn: " + linter.lint(m, rule));
    }

    // --- pure-AST lint: an unguarded number comparison that fires on an empty value (KERNEL-SEMANTICS §2)

    @Test
    void flagsAnUnguardedNumberComparisonThatFiresOnEmpty() {
        // [FEE] < 100 with FEE unguarded: an empty number reads as 0, and 0 < 100 fires — usually unintended.
        Condition c = m.num(FEE).lt(100);

        Diagnostic d = only(linter.lint(c), "RK_UNGUARDED_NUMBER_COMPARISON");
        assertEquals(DiagnosticSource.LINT, d.source());
        assertEquals(Severity.WARNING, d.severity());
        assertTrue(d.summary().contains(FEE), () -> "summary should name the field: " + d.summary());
        // the fix is TWO-SIDED (probe 06): it offers the FieldFilled guard AND acknowledges that leaving it
        // unguarded is intentional when empty-as-violation is meant — so an agent doesn't reflexively add
        // FieldFilled and weaken a deliberate empty-is-a-violation rule.
        assertTrue(d.fix().orElse("").contains("FieldFilled(" + FEE + ")"),
                () -> "fix should still offer the FieldFilled guard: " + d.fix());
        assertTrue(d.fix().orElse("").contains("intentional"),
                () -> "fix should acknowledge the empty-as-violation reading is valid (two-sided): " + d.fix());
    }

    @Test
    void noUnguardedNumberLintWhenGuarded() {
        // FieldFilled(FEE) And [FEE] < 100 — guarded, so an empty value can't reach the comparison.
        Condition c = m.field(FEE).filled().and(m.num(FEE).lt(100));
        assertTrue(noLint(c, "RK_UNGUARDED_NUMBER_COMPARISON"),
                () -> "a guarded number comparison should not be flagged: " + linter.lint(c));
    }

    @Test
    void noUnguardedNumberLintWhenAnEmptyValueCannotFire() {
        // [FEE] > 100 — empty reads as 0, and 0 > 100 is false, so an empty value never fires: no surprise.
        Condition c = m.num(FEE).gt(100);
        assertTrue(noLint(c, "RK_UNGUARDED_NUMBER_COMPARISON"),
                () -> "a comparison an empty-as-0 cannot trip should not be flagged: " + linter.lint(c));
    }

    @Test
    void noUnguardedNumberLintForFieldVsFieldComparison() {
        // [MonthlyFee] < [MonthlyFee]-style field-vs-field is out of scope (no constant operand).
        Condition c = m.num(FEE).lt(m.num("/Subscription/Billing/BaseFee"));
        assertTrue(noLint(c, "RK_UNGUARDED_NUMBER_COMPARISON"),
                () -> "field-vs-field is out of the lint's conservative scope: " + linter.lint(c));
    }

    @Test
    void flagsAnUnguardedNumberComparedAgainstAnAggregate() {
        // [BaseFee] < Sum(Addons*/MonthlyFee), BaseFee unguarded (the T4 shape). The aggregate's value is
        // unknown, but an empty BaseFee reads as 0 and can make the comparison fire spuriously — flag it.
        String base = "/Subscription/Billing/BaseFee";
        Condition c = m.num(base).lt(Dsl.sum(m.flatten("/Subscription/Addons*/MonthlyFee")));

        Diagnostic d = only(linter.lint(c), "RK_UNGUARDED_NUMBER_COMPARISON");
        assertEquals(Severity.WARNING, d.severity());
        assertTrue(d.summary().contains(base), () -> "summary should name the field: " + d.summary());
        assertTrue(d.fix().orElse("").contains("FieldFilled(" + base + ")"),
                () -> "fix should suggest the FieldFilled guard: " + d.fix());
    }

    @Test
    void noAggregateNumberLintWhenGuarded() {
        // FieldFilled(BaseFee) And [BaseFee] < Sum(...) — guarded, so the empty-as-0 can't reach it.
        String base = "/Subscription/Billing/BaseFee";
        Condition c = m.field(base).filled()
                .and(m.num(base).lt(Dsl.sum(m.flatten("/Subscription/Addons*/MonthlyFee"))));
        assertTrue(noLint(c, "RK_UNGUARDED_NUMBER_COMPARISON"),
                () -> "a guarded field-vs-aggregate comparison should not be flagged: " + linter.lint(c));
    }

    // --- pure-AST lint: an unguarded string CONCATENATION comparison (KERNEL-FINDINGS §2, N6 2026-06-08) ---

    @Test
    void flagsAnUnguardedStringConcatComparison() {
        // [CustomerName] != [Street] + " " + [City]: empty Street/City concat to "" (NOT 'not evaluated'),
        // so the comparison evaluates and fires on empty operands — usually unintended.
        BoundModel order = Fixtures.order();
        Condition c = orderStr(order, "/Order/CustomerName").ne(
                orderStr(order, "/Order/ShippingAddress/Street").plus(" ")
                        .plus(orderStr(order, "/Order/ShippingAddress/City")));

        Diagnostic d = only(linter.lint(c), "RK_UNGUARDED_STRING_CONCAT");
        assertEquals(DiagnosticSource.LINT, d.source());
        assertEquals(Severity.WARNING, d.severity());
        assertTrue(d.summary().contains("Street") && d.summary().contains("City"),
                () -> "summary should name the unguarded concat fields: " + d.summary());
        assertTrue(d.fix().orElse("").contains("AllFieldsFilled("),
                () -> "fix should suggest an AllFieldsFilled guard: " + d.fix());
    }

    @Test
    void noConcatLintWhenOperandsAreGuarded() {
        BoundModel order = Fixtures.order();
        Condition c = order.allFieldsFilled("/Order/ShippingAddress/Street", "/Order/ShippingAddress/City")
                .and(orderStr(order, "/Order/CustomerName").ne(
                        orderStr(order, "/Order/ShippingAddress/Street").plus(" ")
                                .plus(orderStr(order, "/Order/ShippingAddress/City"))));
        assertTrue(noLint(c, "RK_UNGUARDED_STRING_CONCAT"),
                () -> "a guarded concatenation comparison should not be flagged: " + linter.lint(c));
    }

    private static io.github.mbackschat.a12.dm.rulekit.dsl.StringExpr orderStr(BoundModel m, String path) {
        return new io.github.mbackschat.a12.dm.rulekit.dsl.StringExpr(
                new io.github.mbackschat.a12.dm.rulekit.dsl.FieldValue(m.field(path)));
    }

    private boolean noLint(Condition c, String code) {
        return linter.lint(c).stream().noneMatch(d -> d.code().filter(code::equals).isPresent());
    }

    private static Diagnostic only(List<Diagnostic> diagnostics, String code) {
        List<Diagnostic> matches = diagnostics.stream()
                .filter(d -> d.code().filter(code::equals).isPresent())
                .toList();
        assertEquals(1, matches.size(), () -> "expected exactly one " + code + ", got: " + diagnostics);
        return matches.get(0);
    }
}
