package io.github.mbackschat.a12.rulekit.lint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.mgmtp.a12.model.notification.Severity;

import io.github.mbackschat.a12.rulekit.Fixtures;
import io.github.mbackschat.a12.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.rulekit.dsl.Condition;
import io.github.mbackschat.a12.rulekit.validate.Diagnostic;
import io.github.mbackschat.a12.rulekit.validate.DiagnosticSource;

/**
 * F4a lint layer (first increment) — valid-but-suspicious conditions the kernel accepts but an LLM
 * shouldn't have written. Pure-AST structural heuristics; model-context lints (unguarded negative
 * under a repeatable group, inverted requiredness) are a later increment.
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

    private static Diagnostic only(List<Diagnostic> diagnostics, String code) {
        List<Diagnostic> matches = diagnostics.stream()
                .filter(d -> d.code().filter(code::equals).isPresent())
                .toList();
        assertEquals(1, matches.size(), () -> "expected exactly one " + code + ", got: " + diagnostics);
        return matches.get(0);
    }
}
