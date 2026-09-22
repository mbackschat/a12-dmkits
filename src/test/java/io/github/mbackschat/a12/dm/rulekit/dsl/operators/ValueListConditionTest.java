package io.github.mbackschat.a12.dm.rulekit.dsl.operators;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.Fixtures;
import io.github.mbackschat.a12.dm.rulekit.dsl.*;

/** Value-list membership (corpus item 5): both grammar shapes — KERNEL-FINDINGS §8. */
class ValueListConditionTest {

    private static final BoundModel m = Fixtures.subscription();
    private static final String NAME = "/Subscription/Addons/Name";
    private static final String MONTHLY_FEE = "/Subscription/Addons/MonthlyFee";

    @Test
    void rendersValueListMembershipBilingually() {
        Condition in = m.str(NAME).in("Backup", "Support");
        assertEquals("FieldValueIncludedInValueList(/Subscription/Addons/Name, \"Backup\", \"Support\")",
                new DslRenderer().render(in));
        assertEquals("FeldWertInWerteListe(/Subscription/Addons/Name, \"Backup\", \"Support\")",
                new DslRenderer(ConditionLanguage.DE).render(in));
        assertEquals("FieldValueNotIncludedInValueList(/Subscription/Addons/Name, \"Backup\")",
                new DslRenderer().render(m.str(NAME).notIn("Backup")));
    }

    @Test
    void numberValueListsAreIntegerOnlyBySignature() {
        // Kernel grammar (verified 2026-06-04): value-list numbers are '-'? digits — a decimal
        // literal is a parse error. The long... signature makes that unrepresentable.
        Condition c = m.num(MONTHLY_FEE).in(0, -10, 20);
        assertEquals("FieldValueIncludedInValueList(/Subscription/Addons/MonthlyFee, 0, -10, 20)",
                new DslRenderer().render(c));
    }

    @Test
    void valueListEntriesCanBeFieldReferences() {
        // DEV-API-GAPS DG10: the kernel allows a value-list ENTRY to be a field reference, not only a literal.
        assertEquals("FieldValueIncludedInValueList(/Subscription/Addons/MonthlyFee, [/Subscription/Billing/BaseFee])",
                new DslRenderer().render(m.num(MONTHLY_FEE).in(m.num("/Subscription/Billing/BaseFee"))));
        assertEquals("FieldValueNotIncludedInValueList(/Subscription/Addons/Name, [/Subscription/Billing/InvoiceEmail])",
                new DslRenderer().render(m.str(NAME).notIn(m.str("/Subscription/Billing/InvoiceEmail"))));
    }

    @Test
    void enumValueListsPreCheckTheDomain() {
        Condition c = m.enumOf("/Subscription/Tier").in("PRO", "ENTERPRISE");
        assertEquals("FieldValueIncludedInValueList(/Subscription/Tier, \"PRO\", \"ENTERPRISE\")",
                new DslRenderer().render(c));
        // outside the domain → fails fast (MVK_INVALID_STRING_CONSTANT_FOR_ENUMERATION pre-checked)
        assertThrows(IllegalArgumentException.class, () -> m.enumOf("/Subscription/Tier").in("PRO", "GOLD"));
    }

    @Test
    void rendersFieldListValueListQuantifiers() {
        Condition c = Dsl.noFieldValueIncludedInValueList(List.of("test", "demo"),
                m.str(NAME), m.str("/Subscription/Billing/InvoiceEmail"));
        assertEquals("NoFieldValueIncludedInValueList(/Subscription/Addons/Name,"
                + " /Subscription/Billing/InvoiceEmail In \"test\", \"demo\")",
                new DslRenderer().render(c));
        assertEquals("KeinFeldWertInWerteListe(/Subscription/Addons/Name,"
                + " /Subscription/Billing/InvoiceEmail In \"test\", \"demo\")",
                new DslRenderer(ConditionLanguage.DE).render(c));

        Condition one = Dsl.atLeastOneFieldValueIncludedInValueList(List.of("PRO"),
                m.enumOf("/Subscription/Tier"));
        assertEquals("AtLeastOneFieldValueIncludedInValueList(/Subscription/Tier In \"PRO\")",
                new DslRenderer().render(one));
    }
}
