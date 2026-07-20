package io.github.mbackschat.a12.dm.rulekit.dsl.operators;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.Fixtures;
import io.github.mbackschat.a12.dm.rulekit.dsl.*;

/** Pattern operators (corpus item 4): {@code PatternMatched}/{@code PatternViolated} — KERNEL-FINDINGS §7. */
class PatternConditionTest {

    private static final BoundModel m = Fixtures.subscription();
    private static final String NAME = "/Subscription/Addons/Name";

    @Test
    void rendersPatternComparisonBilingually() {
        Condition matched = m.str(NAME).matches("[A-Z].*");
        assertEquals("[/Subscription/Addons/Name] PatternMatched \"[A-Z].*\"",
                new DslRenderer().render(matched));
        assertEquals("[/Subscription/Addons/Name] WieMuster \"[A-Z].*\"",
                new DslRenderer(ConditionLanguage.DE).render(matched));

        Condition violated = m.str("/Subscription/Billing/InvoiceEmail").violates(".+@.+");
        assertEquals("[/Subscription/Billing/InvoiceEmail] PatternViolated \".+@.+\"",
                new DslRenderer().render(violated));
        assertEquals("[/Subscription/Billing/InvoiceEmail] NichtWieMuster \".+@.+\"",
                new DslRenderer(ConditionLanguage.DE).render(violated));
    }

    @Test
    void dslFactoryUsesTheSameVerbPairAsTheFluentForm() {
        // D6: the factory was patternMatched/patternViolated while the fluent form was matches/violates
        // — one concept, two names. Unified on matches/violates across both forms.
        assertEquals(new DslRenderer().render(m.str(NAME).matches("[A-Z].*")),
                new DslRenderer().render(Dsl.matches(m.str(NAME), "[A-Z].*")));
        assertEquals(new DslRenderer().render(m.str(NAME).violates("[A-Z].*")),
                new DslRenderer().render(Dsl.violates(m.str(NAME), "[A-Z].*")));
    }

    @Test
    void patternHandleFailsFastOnAJavaInvalidRegex() {
        // Necessary-condition pre-check for MVK_INVALID_PATTERN: anything Java's Pattern rejects,
        // the kernel rejects too. Its additional admission restrictions (e.g. lookbehind is rejected
        // although Java-valid) are checked by the same shared preflight; F4/N7 remains the final oracle.
        assertThrows(IllegalArgumentException.class, () -> m.str(NAME).matches("("));
        assertThrows(IllegalArgumentException.class, () -> m.str(NAME).violates("["));
    }

    @Test
    void patternHandleNamesTheAdditionalKernelBlacklist() {
        var rejected = assertThrows(IllegalArgumentException.class, () -> m.str(NAME).matches("(?<=a)b"));
        assertTrue(rejected.getMessage().contains("pattern-admission gate"), rejected::getMessage);
    }
}
