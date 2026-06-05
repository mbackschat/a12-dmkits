package io.github.mbackschat.a12.dm.rulekit.dsl.operators;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
    void patternHandleFailsFastOnAJavaInvalidRegex() {
        // Necessary-condition pre-check for MVK_INVALID_PATTERN: anything Java's Pattern rejects,
        // the kernel rejects too. The kernel's *portable-subset* gate (e.g. lookbehind is rejected
        // although Java-valid) stays kernel-delegated — F4/N7 has the final word.
        assertThrows(IllegalArgumentException.class, () -> m.str(NAME).matches("("));
        assertThrows(IllegalArgumentException.class, () -> m.str(NAME).violates("["));
    }
}
