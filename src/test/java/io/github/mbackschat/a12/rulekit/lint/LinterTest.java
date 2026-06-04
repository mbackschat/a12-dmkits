package io.github.mbackschat.a12.rulekit.lint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.mgmtp.a12.model.notification.Severity;

import io.github.mbackschat.a12.rulekit.Fixtures;
import io.github.mbackschat.a12.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.rulekit.dsl.Condition;
import io.github.mbackschat.a12.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.rulekit.dsl.Rule;
import io.github.mbackschat.a12.rulekit.validate.Diagnostic;
import io.github.mbackschat.a12.rulekit.validate.DiagnosticSource;

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
                .code("FEE_PRESENT").severity(io.github.mbackschat.a12.rulekit.dsl.Severity.ERROR)
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
                .code("FEE_GUARDED").severity(io.github.mbackschat.a12.rulekit.dsl.Severity.ERROR)
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
                .code("TIER_PRESENT").severity(io.github.mbackschat.a12.rulekit.dsl.Severity.ERROR)
                .message(Dsl.en("Tier required."), Dsl.de("Stufe erforderlich."))
                .build();

        assertTrue(linter.lint(m, rule).stream()
                .noneMatch(d -> d.code().filter("RK_NEG_CONDITION_IN_ITERATION"::equals).isPresent()),
                () -> "non-iterating rule should not warn: " + linter.lint(m, rule));
    }

    private static Diagnostic only(List<Diagnostic> diagnostics, String code) {
        List<Diagnostic> matches = diagnostics.stream()
                .filter(d -> d.code().filter(code::equals).isPresent())
                .toList();
        assertEquals(1, matches.size(), () -> "expected exactly one " + code + ", got: " + diagnostics);
        return matches.get(0);
    }
}
