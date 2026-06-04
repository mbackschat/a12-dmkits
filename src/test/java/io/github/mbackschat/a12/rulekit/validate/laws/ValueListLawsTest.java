package io.github.mbackschat.a12.rulekit.validate.laws;

import org.junit.jupiter.api.Test;

/** KERNEL-FINDINGS §8 — value-list membership slot rules. */
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

    @Test
    void shapeBFieldListsMustBeTypeHomogeneous() {
        KernelLaws.rejects("/Subscription/Billing", "../InvoiceEmail",
                "NoFieldValueIncludedInValueList(InvoiceEmail, BaseFee In \"x\")", "MVK_VARYING_TYPES_NOT_ALLOWED");
    }
}
