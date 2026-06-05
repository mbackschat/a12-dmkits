package io.github.mbackschat.a12.dm.rulekit.dsl.operators;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.Fixtures;
import io.github.mbackschat.a12.dm.rulekit.dsl.*;

/**
 * The iteration cluster (corpus item 7): flattened repetition lists (7a), uniqueness (7b) —
 * KERNEL-FINDINGS §9. Slices 7d/7e ({@code Having}/{@code $}, semantic index) join here.
 */
class IterationConditionTest {

    private static final BoundModel m = Fixtures.subscription();

    @Test
    void rendersFlattenedRepetitionLists() {
        // '*' is path syntax (language-independent), legal only in LIST slots — quantifiers,
        // shape-B value lists, count aggregates. Scalar slots take FieldRef only, so
        // MVK_NO_WILDCARDS_ALLOWED is erased by the FieldListRef/FieldRef type split.
        FieldListRef names = m.flatten("/Subscription/Addons*/Name");
        Condition c = Dsl.atLeastOneFieldFilled(names);
        assertEquals("AtLeastOneFieldFilled(/Subscription/Addons*/Name)", new DslRenderer().render(c));
        assertEquals("MindestensEinFeldAngegeben(/Subscription/Addons*/Name)",
                new DslRenderer(ConditionLanguage.DE).render(c));

        // mixed plain + starred operands are kernel-legal in one predicate
        Condition mixed = Dsl.atLeastOneFieldFilled(m.field("/Subscription/Tier"), names);
        assertEquals("AtLeastOneFieldFilled(/Subscription/Tier, /Subscription/Addons*/Name)",
                new DslRenderer().render(mixed));

        // count aggregate over a repetition list
        assertEquals("NumberOfFilledFields(/Subscription/Addons*/Name, /Subscription/Addons*/MonthlyFee) > 0",
                new DslRenderer().render(Dsl.numberOfFilledFields(
                        names, m.flatten("/Subscription/Addons*/MonthlyFee")).gt(0)));

        // shape-B value list over a repetition list
        assertEquals("NoFieldValueIncludedInValueList(/Subscription/Addons*/Name In \"test\")",
                new DslRenderer().render(Dsl.noFieldValueIncludedInValueList(
                        java.util.List.of("test"), names)));
    }

    @Test
    void flattenValidatesTheStarPlacement() {
        // a star on a non-repeatable group is MVK_INVALID_WILDCARD — pre-checked at flatten()
        assertThrows(IllegalArgumentException.class, () -> m.flatten("/Subscription/Billing*/BaseFee"));
        // the base path must resolve to a field
        assertThrows(IllegalArgumentException.class, () -> m.flatten("/Subscription/Addons*/Nope"));
        // a flatten path without any star is just a field path — reject, the caller wants m.field()
        assertThrows(IllegalArgumentException.class, () -> m.flatten("/Subscription/Addons/Name"));
    }

    @Test
    void rendersRepetitionNotUniqueBilingually() {
        Condition c = Dsl.repetitionNotUnique(m.field("/Subscription/Addons/Name"));
        assertEquals("RepetitionNotUnique(/Subscription/Addons/Name)", new DslRenderer().render(c));
        assertEquals("WiederholungNichtEindeutig(/Subscription/Addons/Name)",
                new DslRenderer(ConditionLanguage.DE).render(c));

        // composite key + @From scope (a LOCALIZED keyword: @From / @Von)
        Condition scoped = Dsl.repetitionNotUnique(
                m.field("/Subscription/Addons/Name"), m.field("/Subscription/Addons/MonthlyFee"))
                .from(m.group("/Subscription/Addons"));
        assertEquals("RepetitionNotUnique(/Subscription/Addons/Name, /Subscription/Addons/MonthlyFee"
                + " @From /Subscription/Addons)", new DslRenderer().render(scoped));
        assertEquals("WiederholungNichtEindeutig(/Subscription/Addons/Name, /Subscription/Addons/MonthlyFee"
                + " @Von /Subscription/Addons)", new DslRenderer(ConditionLanguage.DE).render(scoped));
    }

    @Test
    void repetitionNotUniqueScopeMustBeRepeatable() {
        // Verified 2026-06-04: @From on a non-repeatable group is MVK_GROUP_NOT_REPEATABLE — pre-checked.
        assertThrows(IllegalArgumentException.class,
                () -> Dsl.repetitionNotUnique(m.field("/Subscription/Addons/Name"))
                        .from(m.group("/Subscription/Billing")));
    }

    @Test
    void rendersHavingFilteredRepetitionLists() {
        // Having filters a *-list before an aggregate/quantifier consumes it (KERNEL-FINDINGS §9).
        FieldListRef fees = m.flatten("/Subscription/Addons*/MonthlyFee")
                .having(m.str("/Subscription/Addons/Name").eq("Backup"));
        assertEquals("Sum(/Subscription/Addons*/MonthlyFee Having [/Subscription/Addons/Name] == \"Backup\") > 100",
                new DslRenderer().render(Dsl.sum(fees).gt(100)));
        assertEquals("Summe(/Subscription/Addons*/MonthlyFee Mit [/Subscription/Addons/Name] == \"Backup\") > 100",
                new DslRenderer(ConditionLanguage.DE).render(Dsl.sum(fees).gt(100)));

        // Having also works in a quantifier slot
        FieldListRef names = m.flatten("/Subscription/Addons*/Name")
                .having(m.num("/Subscription/Addons/MonthlyFee").gt(0));
        assertEquals("AtLeastOneFieldFilled(/Subscription/Addons*/Name Having [/Subscription/Addons/MonthlyFee] > 0)",
                new DslRenderer().render(Dsl.atLeastOneFieldFilled(names)));
    }

    @Test
    void havingRejectsForbiddenFilterConstructs() {
        FieldListRef fees = m.flatten("/Subscription/Addons*/MonthlyFee");
        // RepetitionNotUnique in a filter → MVK_DISALLOWED_CONSTRUCT_IN_FILTER_CONDITION (pre-checked)
        assertThrows(IllegalArgumentException.class,
                () -> fees.having(Dsl.repetitionNotUnique(m.field("/Subscription/Addons/Name"))));
        // nested Having (a filtered list inside the filter) → same code (pre-checked)
        Condition nested = Dsl.sum(m.flatten("/Subscription/Addons*/MonthlyFee")
                .having(m.str("/Subscription/Addons/Name").eq("x"))).gt(0);
        assertThrows(IllegalArgumentException.class, () -> fees.having(nested));
    }

    @Test
    void rendersCurrentRepetitionBilingually() {
        // CurrentRepetition(group) is a numeric op = the group's current iteration index (KERNEL-FINDINGS §9).
        Condition c = Dsl.currentRepetition(m.group("/Subscription/Addons")).gt(1);
        assertEquals("CurrentRepetition(/Subscription/Addons) > 1", new DslRenderer().render(c));
        assertEquals("AktuelleWiederholung(/Subscription/Addons) > 1",
                new DslRenderer(ConditionLanguage.DE).render(c));
    }

    @Test
    void currentRepetitionTakesGroupsOnly() {
        // a field arg → MVK_NO_FIELDS_ALLOWED — unrepresentable: currentRepetition takes a GroupRef
        // (the method signature is the guard). No assertion needed beyond compile-time; smoke a group:
        assertEquals("CurrentRepetition(/Subscription/Billing) >= 1",
                new DslRenderer().render(Dsl.currentRepetition(m.group("/Subscription/Billing")).ge(1)));
    }

    @Test
    void rendersSemanticIndexBilingually() {
        // [Field For "value"] — slice 7f; the order fixture's Items group is index-keyed (Sku)
        BoundModel order = Fixtures.order();
        Condition c = order.num("/Order/Items/Count").forIndex("SKU-1").gt(0);
        assertEquals("[/Order/Items/Count For \"SKU-1\"] > 0", new DslRenderer().render(c));
        assertEquals("[/Order/Items/Count Fuer \"SKU-1\"] > 0", new DslRenderer(ConditionLanguage.DE).render(c));
    }

    @Test
    void rendersFieldValuesNotUniqueOverPlainAndStarredOperands() {
        assertEquals("FieldValuesNotUnique(/Subscription/Billing/BaseFee, /Subscription/Billing/EffectiveFee)",
                new DslRenderer().render(Dsl.fieldValuesNotUnique(
                        m.field("/Subscription/Billing/BaseFee"), m.field("/Subscription/Billing/EffectiveFee"))));
        assertEquals("FeldWerteNichtEindeutig(/Subscription/Addons*/Name)",
                new DslRenderer(ConditionLanguage.DE).render(
                        Dsl.fieldValuesNotUnique(m.flatten("/Subscription/Addons*/Name"))));
    }
}
