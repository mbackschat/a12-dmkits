package io.github.mbackschat.a12.dm.rulekit.dsl.operators;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.Fixtures;
import io.github.mbackschat.a12.dm.rulekit.dsl.*;

/** Boolean/confirm literals (corpus item 1) — boolean ≠ confirm (§5b), KERNEL-FINDINGS §1. */
class BooleanConfirmConditionTest {

    private static final BoundModel m = Fixtures.subscription();

    @Test
    void rendersBooleanComparisonBilingually() {
        // Corpus backlog item 1: [Bool] == True is the most frequent gating pattern.
        Condition c = m.bool("/Subscription/AutoRenew").isTrue();
        assertEquals("[/Subscription/AutoRenew] == True", new DslRenderer().render(c));
        assertEquals("[/Subscription/AutoRenew] == Wahr", new DslRenderer(ConditionLanguage.DE).render(c));

        assertEquals("[/Subscription/AutoRenew] == False",
                new DslRenderer().render(m.bool("/Subscription/AutoRenew").isFalse()));
        assertEquals("[/Subscription/AutoRenew] == Falsch",
                new DslRenderer(ConditionLanguage.DE).render(m.bool("/Subscription/AutoRenew").isFalse()));
    }

    @Test
    void confirmHandleOffersOnlyKernelLegalComparisons() {
        // Confirm compares only to True (MVK_INVALID_COMPARE_TO_YES otherwise — verified, see
        // KERNEL-INTEGRATION-NOTES): the handle has confirmed()/notConfirmed(), no isFalse().
        assertEquals("[/Subscription/TermsAccepted] == True",
                new DslRenderer().render(m.confirm("/Subscription/TermsAccepted").confirmed()));
        assertEquals("[/Subscription/TermsAccepted] != True",
                new DslRenderer().render(m.confirm("/Subscription/TermsAccepted").notConfirmed()));
    }

    @Test
    void typedBoolAndConfirmHandlesRejectWrongKinds() {
        assertThrows(IllegalArgumentException.class, () -> m.bool("/Subscription/Addons/Name"));
        assertThrows(IllegalArgumentException.class, () -> m.confirm("/Subscription/AutoRenew"));
        assertThrows(IllegalArgumentException.class, () -> m.bool("/Subscription/TermsAccepted"));
    }
}
