package io.github.mbackschat.a12.rulekit.validate.laws;

import org.junit.jupiter.api.Test;

/** KERNEL-FINDINGS §5 — numeric literals are exempt from the scale check; string ordering is illegal. */
class NumberLawsTest {

    @Test
    void scaleFreeNumericLiteralsAreAcceptedOnEquality() {
        // The decimal-scale check (MVK_INVALID_COMPARE_DEC_PLACES) applies to field-vs-field only —
        // numeric LITERALS are accepted at any written scale against a scale-2 field.
        for (String cond : new String[]{"[MonthlyFee] == 0", "[MonthlyFee] == 0.0", "[MonthlyFee] != 0"}) {
            KernelLaws.accepts("/Subscription/Addons", "../MonthlyFee", "FieldFilled(MonthlyFee) And " + cond);
        }
    }

    @Test
    void orderingAStringIsATypeError() {
        KernelLaws.rejects("/Subscription/Addons", "../Name",
                "FieldFilled(Name) And [Name] <= [MonthlyFee]", "MVK_INVALID_TYPE_FOR_COMPARISON");
    }

    @Test
    void fieldVsFieldScaleGateAppliesToEqualityOnly() {
        // Verified 2026-06-04: == / != between two number fields of DIFFERENT scale (Quantity 0,
        // UnitPrice 2) → MVK_INVALID_COMPARE_DEC_PLACES; ORDERING (< <= > >=) is exempt.
        KernelLaws.rejectsOrder("/Order", "../Quantity",
                "AllFieldsFilled(Quantity, UnitPrice) And [Quantity] == [UnitPrice]",
                "MVK_INVALID_COMPARE_DEC_PLACES");
        KernelLaws.acceptsOrder("/Order", "../Quantity",
                "AllFieldsFilled(Quantity, UnitPrice) And [Quantity] < [UnitPrice]");
    }

    @Test
    void stringFieldVsFieldEqualityIsAccepted() {
        KernelLaws.acceptsOrder("/Order", "../CustomerName",
                "AllFieldsFilled(CustomerName, ProductCode) And [CustomerName] == [ProductCode]");
    }
}
