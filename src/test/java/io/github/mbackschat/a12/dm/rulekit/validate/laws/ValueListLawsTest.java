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

    /**
     * <b>And so is every TEMPORAL kind</b> — the row that puts this whole carrier outside the declared-format
     * rule. Every temporal gate in the estate reads a field's declared FORMAT rather than its kind
     * ([KF271]), and two classifiers in {@code ConditionConverter} deliberately still read the kind at this
     * carrier ({@code homogeneousKind}, {@code convertValueList}). That is only correct while no temporal
     * field can be a value-list operand at all — which the BOOLEAN row above does not establish, since it
     * measures one kind and this is a claim about a family (added 2026-09-07: the sites were reasoned
     * unaffected, and a reasoned exclusion is the one that needs the row).
     */
    @Test
    void everyTemporalKindIsExcludedToo() {
        KernelLaws.rejectsOrder("/Order", "../OrderDate",
                "FieldValueIncludedInValueList(OrderDate, \"15.06.2024\")",
                "MVK_ONLY_STRING_ENUM_NUMBER_ALLOWED");
        KernelLaws.rejectsOrder("/Order", "../PickupTime",
                "FieldValueIncludedInValueList(PickupTime, \"17:00:00\")",
                "MVK_ONLY_STRING_ENUM_NUMBER_ALLOWED");
        KernelLaws.rejectsOrder("/Order", "../ScheduledAt",
                "FieldValueIncludedInValueList(ScheduledAt, \"15.06.2024\")",
                "MVK_ONLY_STRING_ENUM_NUMBER_ALLOWED");
        KernelLaws.rejectsOrder("/Order", "../CoverageWindow",
                "FieldValueIncludedInValueList(CoverageWindow, \"15.06.2024/16.06.2024\")",
                "MVK_ONLY_STRING_ENUM_NUMBER_ALLOWED");
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
