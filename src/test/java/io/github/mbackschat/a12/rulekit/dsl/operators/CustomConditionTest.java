package io.github.mbackschat.a12.rulekit.dsl.operators;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.rulekit.Fixtures;
import io.github.mbackschat.a12.rulekit.dsl.*;

/** CustomCondition (corpus item 8): the app-defined escape hatch — KERNEL-SEMANTICS §14. */
class CustomConditionTest {

    private static final BoundModel m = Fixtures.subscription();

    @Test
    void rendersCustomConditionBilingually() {
        Condition c = m.field("/Subscription/Tier").filled().and(Dsl.customCondition("NotReverse"));
        assertEquals("FieldFilled(/Subscription/Tier) And CustomCondition NotReverse",
                new DslRenderer().render(c));
        assertEquals("FeldAngegeben(/Subscription/Tier) Und ApplikationsBedingung NotReverse",
                new DslRenderer(ConditionLanguage.DE).render(c));
    }

    @Test
    void nameMustBeABareIdentifier() {
        assertThrows(IllegalArgumentException.class, () -> Dsl.customCondition(""));
        assertThrows(IllegalArgumentException.class, () -> Dsl.customCondition("has space"));
        assertThrows(IllegalArgumentException.class, () -> Dsl.customCondition("/path/like"));
        assertEquals("CustomCondition My_Check2",
                new DslRenderer().render(Dsl.customCondition("My_Check2")));
    }

    @Test
    void forbiddenInsideAHavingFilter() {
        // MVK_DISALLOWED_CONSTRUCT_IN_FILTER_CONDITION (verified 2026-06-04) — pre-checked in having()
        assertThrows(IllegalArgumentException.class,
                () -> m.flatten("/Subscription/Addons*/MonthlyFee").having(Dsl.customCondition("NotReverse")));
    }

    @Test
    void forbiddenInAComputationPrecondition() {
        // KERNEL-SEMANTICS §14 — pre-checked in Computation.build()
        assertThrows(IllegalStateException.class,
                () -> m.computation(m.num("/Subscription/Billing/EffectiveFee"))
                        .when(m.field("/Subscription/Tier").filled().and(Dsl.customCondition("NotReverse")))
                        .calc(m.num("/Subscription/Billing/BaseFee").value())
                        .message(Dsl.en("x"), Dsl.de("y"))
                        .build());
    }
}
