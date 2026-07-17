package io.github.mbackschat.a12.dm.rulekit.lint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.Fixtures;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.validate.Diagnostic;

/**
 * G41 — the ADVISORY-lane flag for an unguarded numeric <b>field-vs-field ordering</b> ({@code [A] < [B]}).
 * Substituting one side empty leaves the other unpinned, so the PRECISE lane can't decide it; but the kernel
 * reads whichever side is empty as 0, so the comparison can fire on the ABSENCE of either field
 * data-dependently ({@code 0 < [B]} when B &gt; 0; {@code [A] < 0} when A &lt; 0). Advisory, and only when
 * NEITHER field is presence-guarded (a guarded side is a deliberate author choice, left alone). Scoped to
 * ordering — equality/inequality field-vs-field is out (its own scale gate + a different trap shape).
 */
class UnguardedFieldVsFieldLintTest {

    private static final BoundModel m = Fixtures.subscription();
    private static final String BASE = "/Subscription/Billing/BaseFee";
    private static final String EFF = "/Subscription/Billing/EffectiveFee";

    private final Linter linter = new Linter();

    private boolean flagged(Condition c) {
        return linter.lint(c).stream()
                .anyMatch(d -> d.code().filter("RK_UNGUARDED_NUMBER_COMPARISON"::equals).isPresent());
    }

    @Test
    void unguardedFieldVsFieldOrderingIsFlagged() {
        Condition c = m.num(BASE).lt(m.num(EFF));
        Diagnostic d = linter.lint(c).stream()
                .filter(x -> x.code().filter("RK_UNGUARDED_NUMBER_COMPARISON"::equals).isPresent())
                .findFirst().orElseThrow(() -> new AssertionError("expected the advisory: " + linter.lint(c)));
        assertTrue(d.summary().contains("BaseFee") && d.summary().contains("EffectiveFee"),
                () -> "names both unguarded fields: " + d.summary());
    }

    @Test
    void bothSidesGuardedIsNotFlagged() {
        Condition c = m.allFieldsFilled(BASE, EFF).and(m.num(BASE).lt(m.num(EFF)));
        assertTrue(!flagged(c), () -> "both guarded → no advisory: " + linter.lint(c));
    }

    @Test
    void oneSideGuardedIsNotFlagged() {
        // the advisory is scoped to "NEITHER side guarded" — a partial guard is a deliberate author choice.
        Condition c = m.field(BASE).filled().and(m.num(BASE).lt(m.num(EFF)));
        assertTrue(!flagged(c), () -> "one side guarded → no advisory: " + linter.lint(c));
    }

    @Test
    void equalityFieldVsFieldIsNotFlagged() {
        // ordering only — equality field-vs-field is a different shape (scale gate; both-empty 0==0).
        Condition c = m.num(BASE).eq(m.num(EFF));
        assertEquals(0, linter.lint(c).stream()
                .filter(d -> d.code().filter("RK_UNGUARDED_NUMBER_COMPARISON"::equals).isPresent()).count(),
                () -> "equality is out of scope: " + linter.lint(c));
    }
}
