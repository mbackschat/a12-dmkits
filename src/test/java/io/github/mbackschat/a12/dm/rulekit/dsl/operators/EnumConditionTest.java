package io.github.mbackschat.a12.dm.rulekit.dsl.operators;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.Fixtures;
import io.github.mbackschat.a12.dm.rulekit.dsl.*;

/** Enums (corpus item 3): comparison by stored value + category navigation — KERNEL-FINDINGS §8. */
class EnumConditionTest {

    private static final BoundModel m = Fixtures.subscription();

    @Test
    void rendersEnumComparisonByStoredValue() {
        Condition c = m.enumOf("/Subscription/Tier").is("PRO");
        assertEquals("[/Subscription/Tier] == \"PRO\"", new DslRenderer().render(c));
    }

    @Test
    void rendersEnumCategoryNavigation() {
        // Corpus item 3: [Field -> Category] compares against the CATEGORY's value domain.
        Condition c = m.enumOf("/Subscription/Tier").category("Pricing").is("PAID");
        assertEquals("[/Subscription/Tier -> Pricing] == \"PAID\"", new DslRenderer().render(c));
        // the arrow is symbolic — identical in German:
        assertEquals("[/Subscription/Tier -> Pricing] != \"FREE\"",
                new DslRenderer(ConditionLanguage.DE).render(
                        m.enumOf("/Subscription/Tier").category("Pricing").isNot("FREE")));
    }

    @Test
    void categoryHandleErasesTheVerifiedCategoryMistakes() {
        EnumRef tier = m.enumOf("/Subscription/Tier");
        // unknown category — MVK_CATEGORY_UNKOWN erased at handle creation:
        assertThrows(IllegalArgumentException.class, () -> tier.category("Nope"));
        // enum value instead of category value — MVK_INVALID_STRING_CONSTANT_FOR_ENUM_COMPARISON erased:
        assertThrows(IllegalArgumentException.class, () -> tier.category("Pricing").is("BASIC"));
        // -> on a non-enum is unrepresentable: only EnumRef has category() (MVK_NO_ENUMERATION erased)
    }

    @Test
    void enumHandleRejectsAValueOutsideTheModelDomain() {
        assertThrows(IllegalArgumentException.class, () -> m.enumOf("/Subscription/Tier").is("PLATINUM"));
    }

    @Test
    void typedEnumHandleRejectsANonEnumField() {
        assertThrows(IllegalArgumentException.class, () -> m.enumOf("/Subscription/Addons/MonthlyFee"));
    }
}
