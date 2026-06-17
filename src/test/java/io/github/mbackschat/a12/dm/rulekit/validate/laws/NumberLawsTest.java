package io.github.mbackschat.a12.dm.rulekit.validate.laws;

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

    @Test
    void multiplicationRaisesScaleSoEqualityRejectsEvenWithMatchingFieldScales() {
        // UnitPrice is scale 2; [UnitPrice] * [UnitPrice] is scale 4, so the ==/!= scale gate rejects
        // even though every field shares a scale. Wrapping the product in RoundAccounting(...,2) fixes it.
        KernelLaws.rejectsOrder("/Order", "../UnitPrice",
                "FieldFilled(UnitPrice) And [UnitPrice] == [UnitPrice] * [UnitPrice]",
                "MVK_INVALID_COMPARE_DEC_PLACES");
        KernelLaws.acceptsOrder("/Order", "../UnitPrice",
                "FieldFilled(UnitPrice) And [UnitPrice] == RoundAccounting([UnitPrice] * [UnitPrice], 2)");
    }

    @Test
    void powerOperatorRejectsUnparenthesizedNesting() {
        // KERNEL-FINDINGS §5 / BA "power operator": a^b^c is rejected — nesting needs explicit grouping.
        KernelLaws.rejectsOrder("/Order", "../Quantity",
                "FieldFilled(Quantity) And [Quantity] ^ [Quantity] ^ [Quantity] > 1",
                "MVK_TOO_MANY_POW_FOR_CALC");
    }

    @Test
    void powerExponentMustHaveNoDecimalPlaces() {
        // The exponent must be a whole number: a scale-2 field (UnitPrice) is rejected; a scale-0 field
        // (Quantity) is a valid exponent. (CheckOperationImpl.checkNoFractionalDigitsInExponent.)
        KernelLaws.rejectsOrder("/Order", "../Quantity",
                "AllFieldsFilled(Quantity, UnitPrice) And [Quantity] ^ [UnitPrice] > 1",
                "MVK_INVALID_NUMBER_FOR_EXP");
        KernelLaws.acceptsOrder("/Order", "../Quantity",
                "AllFieldsFilled(Quantity, UnitPrice) And [UnitPrice] ^ [Quantity] > 1");
    }
}
