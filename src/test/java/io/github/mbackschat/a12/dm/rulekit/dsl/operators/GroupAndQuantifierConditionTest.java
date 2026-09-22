package io.github.mbackschat.a12.dm.rulekit.dsl.operators;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
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

    @Test
    void listOverloadsCompleteTheQuantifierMatrix() {
        // D3: allOf/anyOf + allFieldsFilled/noFieldFilled/atLeastOneFieldFilled carried List peers; the rest
        // of the field-fill family and ALL group quantifiers did not. Each List form folds like the varargs.
        DslRenderer r = new DslRenderer();
        FieldRef a = m.field(NAME);
        FieldRef b = m.field(MONTHLY_FEE);
        List<FieldRef> fields = List.of(a, b);
        assertEquals(r.render(Dsl.notAllFieldsFilled(a, b)), r.render(Dsl.notAllFieldsFilled(fields)));
        assertEquals(r.render(Dsl.fieldsNotCollectivelyFilled(a, b)), r.render(Dsl.fieldsNotCollectivelyFilled(fields)));
        assertEquals(r.render(Dsl.notExactlyOneFieldFilled(a, b)), r.render(Dsl.notExactlyOneFieldFilled(fields)));
        assertEquals(r.render(Dsl.moreThanOneFieldFilled(a, b)), r.render(Dsl.moreThanOneFieldFilled(fields)));
        assertEquals(r.render(Dsl.fieldValuesNotUnique(a, b)), r.render(Dsl.fieldValuesNotUnique(fields)));

        GroupRef g1 = m.group("/Subscription/Addons");
        GroupRef g2 = m.group("/Subscription/Billing");
        List<GroupRef> groups = List.of(g1, g2);
        assertEquals(r.render(Dsl.allGroupsFilled(g1, g2)), r.render(Dsl.allGroupsFilled(groups)));
        assertEquals(r.render(Dsl.noGroupFilled(g1, g2)), r.render(Dsl.noGroupFilled(groups)));
        assertEquals(r.render(Dsl.atLeastOneGroupFilled(g1, g2)), r.render(Dsl.atLeastOneGroupFilled(groups)));
        assertEquals(r.render(Dsl.notAllGroupsFilled(g1, g2)), r.render(Dsl.notAllGroupsFilled(groups)));
        assertEquals(r.render(Dsl.groupsNotCollectivelyFilled(g1, g2)), r.render(Dsl.groupsNotCollectivelyFilled(groups)));

        // an empty list is an error, matching the allFieldsFilled(List)/allOf(List) contract
        assertThrows(IllegalArgumentException.class, () -> Dsl.moreThanOneFieldFilled(List.<FieldRef>of()));
        assertThrows(IllegalArgumentException.class, () -> Dsl.allGroupsFilled(List.<GroupRef>of()));
    }
}
