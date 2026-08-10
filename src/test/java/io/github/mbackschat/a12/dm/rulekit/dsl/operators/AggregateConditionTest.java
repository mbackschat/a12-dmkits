package io.github.mbackschat.a12.dm.rulekit.dsl.operators;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.Fixtures;
import io.github.mbackschat.a12.dm.rulekit.dsl.*;

/**
 * The aggregates (corpus item 6 + slice 7c): filled counts, {@code Sum}/{@code MaxValue}/{@code MinValue},
 * {@code NumberOfDifferentValues} — KERNEL-FINDINGS §2/§9.
 */
class AggregateConditionTest {

    private static final BoundModel m = Fixtures.subscription();

    @Test
    void rendersFilledCountAggregatesBilingually() {
        // ≥2 fields by signature (MVK_PARAMSIZE_INVALIDN erased); mixed kinds are legal
        Condition c = Dsl.numberOfFilledFields(
                m.field("/Subscription/Tier"), m.field("/Subscription/AutoRenew")).lt(2);
        assertEquals("NumberOfFilledFields(/Subscription/Tier, /Subscription/AutoRenew) < 2",
                new DslRenderer().render(c));
        assertEquals("AnzahlAngegebenerFelder(/Subscription/Tier, /Subscription/AutoRenew) < 2",
                new DslRenderer(ConditionLanguage.DE).render(c));

        // the single-GROUP form counts filled fields inside the group
        assertEquals("NumberOfFilledFields(/Subscription/Billing) < 3",
                new DslRenderer().render(Dsl.numberOfFilledFields(m.group("/Subscription/Billing")).lt(3)));

        // NumberOfFilledGroups needs ≥2 groups (MVK_PARAMSIZE_INVALIDGN erased by signature)
        Condition g = Dsl.numberOfFilledGroups(
                m.group("/Subscription/Addons"), m.group("/Subscription/Billing")).eq(2);
        assertEquals("NumberOfFilledGroups(/Subscription/Addons*, /Subscription/Billing) == 2",
                new DslRenderer().render(g));
        assertEquals("AnzahlAngegebenerKontexte(/Subscription/Addons*, /Subscription/Billing) == 2",
                new DslRenderer(ConditionLanguage.DE).render(g));
    }

    @Test
    void rendersNumberOfFilledGroupsOverASingleRepeatableGroupAsAWildcardScope() {
        // a lone repeatable group is the wildcard form Group* — counts that group's filled repetitions
        Condition c = Dsl.numberOfFilledGroups(m.group("/Subscription/Addons")).gt(1);
        assertEquals("NumberOfFilledGroups(/Subscription/Addons*) > 1", new DslRenderer().render(c));
        assertEquals("AnzahlAngegebenerKontexte(/Subscription/Addons*) > 1",
                new DslRenderer(ConditionLanguage.DE).render(c));
        // a single non-repeating group is still rejected (only the wildcard form is meaningful)
        assertThrows(IllegalArgumentException.class,
                () -> Dsl.numberOfFilledGroups(m.group("/Subscription/Billing")));
    }

    @Test
    void filledCountAggregatesRejectRootGroups() {
        // Verified 2026-06-04: a root group draws MVK_ROOT_GROUP_REFERENCED — pre-checked here.
        assertThrows(IllegalArgumentException.class,
                () -> Dsl.numberOfFilledFields(m.group("/Subscription")));
        assertThrows(IllegalArgumentException.class,
                () -> Dsl.numberOfFilledGroups(m.group("/Subscription"), m.group("/Subscription/Addons")));
    }

    @Test
    void rendersListAggregatesBilingually() {
        Condition c = Dsl.sum(m.flatten("/Subscription/Addons*/MonthlyFee")).gt("100.00");
        assertEquals("Sum(/Subscription/Addons*/MonthlyFee) > 100.00", new DslRenderer().render(c));
        assertEquals("Summe(/Subscription/Addons*/MonthlyFee) > 100.00",
                new DslRenderer(ConditionLanguage.DE).render(c));

        assertEquals("MaxWert(/Subscription/Addons*/MonthlyFee) > 50",
                new DslRenderer(ConditionLanguage.DE).render(
                        Dsl.maxValue(m.flatten("/Subscription/Addons*/MonthlyFee")).gt(50)));
        assertEquals("MinValue(/Subscription/Billing/BaseFee, /Subscription/Billing/EffectiveFee) >= 0",
                new DslRenderer().render(Dsl.minValue(
                        m.field("/Subscription/Billing/BaseFee"), m.field("/Subscription/Billing/EffectiveFee")).ge(0)));
        assertEquals("AnzahlVerschiedenerWerte(/Subscription/Addons*/Name) < 2",
                new DslRenderer(ConditionLanguage.DE).render(
                        Dsl.numberOfDifferentValues(m.flatten("/Subscription/Addons*/Name")).lt(2)));
    }

    /**
     * Temporal operands are legal for {@code NumberOfDifferentValues} — the kernel's own code name says so
     * ({@code MVK_ONLY_STRING_ENUM_NUMBER_CMP_DATE_ALLOWED}) and it accepts DATE, TIME and DATETIME pairs
     * (`validate.laws.AggregateSlotLawsTest`). Unlike Max/Min there is no date-family alternative factory, so
     * refusing them left the form unauthorable. The two remaining temporal laws stay F4-arbitrated: mixing a date
     * with a non-date is {@code MVK_DATE_AND_NONDATE}, and incompatible formats {@code MVK_DATEFORMATS_NOT_COMPATIBLE}.
     */
    @Test
    void numberOfDifferentValuesAcceptsTemporalOperands() {
        BoundModel o = Fixtures.order();
        assertEquals("NumberOfDifferentValues(/Order/OrderDate, /Order/DeliveryDate) > 0",
                new DslRenderer().render(Dsl.numberOfDifferentValues(
                        o.field("/Order/OrderDate"), o.field("/Order/DeliveryDate")).gt(0)));
        assertEquals("NumberOfDifferentValues(/Order/ScheduledAt, /Order/CompletedAt) > 0",
                new DslRenderer().render(Dsl.numberOfDifferentValues(
                        o.field("/Order/ScheduledAt"), o.field("/Order/CompletedAt")).gt(0)));
        // the homogeneity partition now reports the kernel's own temporal code
        assertTrue(assertThrows(IllegalArgumentException.class, () -> Dsl.numberOfDifferentValues(
                o.field("/Order/OrderDate"), o.field("/Order/Quantity"))).getMessage()
                .contains("MVK_DATE_AND_NONDATE"));
    }

    @Test
    void listAggregatesPreCheckTheVerifiedSlotRules() {
        // number operands only (MVK_NO_NUMBER / MVK_NOT_SORTABLE)
        assertThrows(IllegalArgumentException.class,
                () -> Dsl.sum(m.field("/Subscription/Addons/Name"), m.field("/Subscription/Billing/InvoiceEmail")));
        // param law: a single plain field is too few — but a single repetition list is fine
        assertThrows(IllegalArgumentException.class, () -> Dsl.sum(m.field("/Subscription/Billing/BaseFee")));
        // duplicates (MVK_DUPLICATE_PARAM1)
        assertThrows(IllegalArgumentException.class,
                () -> Dsl.sum(m.field("/Subscription/Billing/BaseFee"), m.field("/Subscription/Billing/BaseFee")));
        // NumberOfDifferentValues homogeneity + the bool/confirm ban
        assertThrows(IllegalArgumentException.class,
                () -> Dsl.numberOfDifferentValues(
                        m.field("/Subscription/Addons/Name"), m.field("/Subscription/Addons/MonthlyFee")));
        assertThrows(IllegalArgumentException.class,
                () -> Dsl.numberOfDifferentValues(
                        m.field("/Subscription/AutoRenew"), m.field("/Subscription/TermsAccepted")));
    }
}
