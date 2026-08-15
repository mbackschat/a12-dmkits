package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import org.junit.jupiter.api.Test;

/**
 * KERNEL-FINDINGS §8 — value-list membership slot rules.
 *
 * <p>{@link #anIntegerEntryIsAdmitted} is the control the refusals below need, and the first of them needs it
 * most: a decimal entry is a GRAMMAR-level parse error carrying no {@code MVK_} code, so that assertion would
 * pass just as well against a missing field, a misspelled operator, or an unusable fixture. The accept row makes
 * the refusal attributable to the decimal (control added 2026-08-15).
 */
class ValueListLawsTest {

    @Test
    void numbersInValueListsAreIntegerOnly() {
        // a decimal is a grammar-level parse error, even against a scale-2 field
        KernelLaws.rejects("/Subscription/Billing", "../BaseFee",
                "FieldValueIncludedInValueList(BaseFee, 10.50)");
    }

    @Test
    void booleanFieldsAreExcluded() {
        KernelLaws.rejects("/Subscription", "../AutoRenew",
                "FieldValueIncludedInValueList(AutoRenew, \"x\")", "MVK_ONLY_STRING_ENUM_NUMBER_ALLOWED");
    }

    /** The same operator and field with an INTEGER entry — so only the decimal is what the first law refuses. */
    @Test
    void anIntegerEntryIsAdmitted() {
        KernelLaws.accepts("/Subscription/Billing", "../BaseFee",
                "FieldValueIncludedInValueList(BaseFee, 10)");
    }

    @Test
    void shapeBFieldListsMustBeTypeHomogeneous() {
        KernelLaws.rejects("/Subscription/Billing", "../InvoiceEmail",
                "NoFieldValueIncludedInValueList(InvoiceEmail, BaseFee In \"x\")", "MVK_VARYING_TYPES_NOT_ALLOWED");
    }
}
