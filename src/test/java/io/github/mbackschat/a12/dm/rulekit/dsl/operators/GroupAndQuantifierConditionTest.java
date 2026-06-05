package io.github.mbackschat.a12.dm.rulekit.dsl.operators;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.Fixtures;
import io.github.mbackschat.a12.dm.rulekit.dsl.*;

/**
 * Field-list quantifiers + group predicates (corpus item 2 and the quantifier family) —
 * type-directional: GroupFilled(field)/FieldFilled(group) are unrepresentable (KERNEL-FINDINGS §9).
 */
class GroupAndQuantifierConditionTest {

    private static final BoundModel m = Fixtures.subscription();
    private static final String NAME = "/Subscription/Addons/Name";
    private static final String MONTHLY_FEE = "/Subscription/Addons/MonthlyFee";

    @Test
    void rendersFieldListQuantifierBilingually() {
        Condition c = m.allFieldsFilled(NAME, MONTHLY_FEE);
        assertEquals("AllFieldsFilled(/Subscription/Addons/Name, /Subscription/Addons/MonthlyFee)",
                new DslRenderer().render(c));
        assertEquals("AlleFelderAngegeben(/Subscription/Addons/Name, /Subscription/Addons/MonthlyFee)",
                new DslRenderer(ConditionLanguage.DE).render(c));
    }

    @Test
    void fieldListQuantifierValidatesEveryPath() {
        assertThrows(IllegalArgumentException.class, () -> m.allFieldsFilled(NAME, "/Subscription/Addons/Nope"));
    }

    @Test
    void rendersGroupPresenceBilingually() {
        // Corpus backlog item 2: GroupFilled is the highest-frequency previously-uncovered construct.
        Condition c = m.group("/Subscription/Addons").filled();
        assertEquals("GroupFilled(/Subscription/Addons)", new DslRenderer().render(c));
        assertEquals("KontextAngegeben(/Subscription/Addons)", new DslRenderer(ConditionLanguage.DE).render(c));

        assertEquals("GroupNotFilled(/Subscription/Billing)",
                new DslRenderer().render(m.group("/Subscription/Billing").notFilled()));
    }

    @Test
    void rendersGroupListQuantifierBilingually() {
        Condition c = Dsl.atLeastOneGroupFilled(m.group("/Subscription/Addons"), m.group("/Subscription/Billing"));
        assertEquals("AtLeastOneGroupFilled(/Subscription/Addons, /Subscription/Billing)",
                new DslRenderer().render(c));
        assertEquals("MindestensEinKontextAngegeben(/Subscription/Addons, /Subscription/Billing)",
                new DslRenderer(ConditionLanguage.DE).render(c));
    }

    @Test
    void groupHandleRejectsAFieldPathAndViceVersa() {
        // GroupFilled(field) is MVK_NO_FIELDS_ALLOWED, FieldFilled(group) is MVK_NO_GROUPS_ALLOWED
        // (verified — see KERNEL-INTEGRATION-NOTES): the typed handles erase both classes.
        assertThrows(IllegalArgumentException.class, () -> m.group(MONTHLY_FEE));
        assertThrows(IllegalArgumentException.class, () -> m.field("/Subscription/Addons"));
    }

    @Test
    void groupHandleKnowsRepeatability() {
        assertEquals(true, m.group("/Subscription/Addons").repeating());
        assertEquals(false, m.group("/Subscription/Billing").repeating());
    }
}
