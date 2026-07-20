package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import org.junit.jupiter.api.Test;

/** KERNEL-FINDINGS §5 — derived numeric scale/capability, arithmetic authoring regions, and string ordering. */
class NumberLawsTest {

    @Test
    void onlyACapableSmallerScaleSideMayBePadded() {
        KernelLaws.acceptsOrder("/Order", "../UnitPrice",
                "FieldFilled(UnitPrice) And [UnitPrice] == 0");
        KernelLaws.acceptsOrder("/Order", "../UnitPrice",
                "FieldFilled(UnitPrice) And 0 == [UnitPrice]");
        KernelLaws.acceptsOrder("/Order", "../Quantity",
                "FieldFilled(Quantity) And [Quantity] == 1000");

        KernelLaws.rejectsOrder("/Order", "../Quantity",
                "FieldFilled(Quantity) And [Quantity] == 0.00",
                "MVK_INVALID_COMPARE_DEC_PLACES");
        KernelLaws.rejectsOrder("/Order", "../Quantity",
                "FieldFilled(Quantity) And 0.00 == [Quantity]",
                "MVK_INVALID_COMPARE_DEC_PLACES");
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
    void roundingDecimalPlacesAboveFourteenIsRejected() {
        // Source CheckOpUtils: reject if dec > 14 || dec < 0 — valid range is 0..14 (the catalog's
        // RoundDown(...,0) example confirms 0 is allowed; KERNEL-SEMANTICS's "1 to 14" is slightly off).
        KernelLaws.rejectsOrder("/Order", "../UnitPrice",
                "FieldFilled(UnitPrice) And RoundDown([UnitPrice], 15) > 1", "MVK_INVALID_DIGIT_FOR_ROUNDING");
    }

    @Test
    void powerOperatorRejectsUnparenthesizedNesting() {
        // Only the parser-reachable direct-left chain is rejected. Either explicit grouping is a fresh region.
        KernelLaws.rejectsOrder("/Order", "../Quantity",
                "FieldFilled(Quantity) And [Quantity] ^ [Quantity] ^ [Quantity] > 1",
                "MVK_TOO_MANY_POW_FOR_CALC");
        KernelLaws.acceptsOrder("/Order", "../Quantity",
                "FieldFilled(Quantity) And { [Quantity] ^ [Quantity] } ^ [Quantity] > 1");
        KernelLaws.acceptsOrder("/Order", "../Quantity",
                "FieldFilled(Quantity) And [Quantity] ^ { [Quantity] ^ 2 } > 1");
    }

    @Test
    void powerExponentMustHaveNoDecimalPlaces() {
        // This is a shared authoring rejection before either runtime strategy can legally execute the rule.
        // A scale-2 field and a fractional literal are rejected; a scale-0 field is a valid exponent.
        KernelLaws.rejectsOrder("/Order", "../Quantity",
                "AllFieldsFilled(Quantity, UnitPrice) And [Quantity] ^ [UnitPrice] > 1",
                "MVK_INVALID_NUMBER_FOR_EXP");
        KernelLaws.rejectsOrder("/Order", "../Quantity",
                "FieldFilled(Quantity) And [Quantity] ^ 0.5 > 1",
                "MVK_INVALID_NUMBER_FOR_EXP");
        KernelLaws.acceptsOrder("/Order", "../Quantity",
                "AllFieldsFilled(Quantity, UnitPrice) And [UnitPrice] ^ [Quantity] > 1");
    }

    @Test
    void powerScaleZeroExceptionStillClearsConstantExpandability() {
        KernelLaws.acceptsOrder("/Order", "../Quantity",
                "FieldFilled(Quantity) And [Quantity] == [Quantity] ^ 0");
        KernelLaws.rejectsOrder("/Order", "../UnitPrice",
                "FieldFilled(UnitPrice) And [UnitPrice] == 0 ^ 2",
                "MVK_INVALID_COMPARE_DEC_PLACES");
        KernelLaws.rejectsOrder("/Order", "../Quantity",
                "FieldFilled(Quantity) And [Quantity] == [Quantity] ^ -1",
                "MVK_INVALID_COMPARE_DEC_PLACES");
        KernelLaws.acceptsOrder("/Order", "../Quantity",
                "FieldFilled(Quantity) And [Quantity] < [Quantity] ^ -1");
    }

    @Test
    void plainArithmeticCountsDivisionContributionsUntilAReset() {
        KernelLaws.rejectsOrder("/Order", "../Quantity",
                "FieldFilled(Quantity) And [Quantity] / 2 / 3 > 0",
                "MVK_TOO_MANY_DIV_FOR_CALC");
        KernelLaws.acceptsOrder("/Order", "../Quantity",
                "FieldFilled(Quantity) And { [Quantity] / 2 } / 3 > 0");
        KernelLaws.acceptsOrder("/Order", "../Quantity",
                "FieldFilled(Quantity) And [Quantity] / { 2 / 3 } > 0");
        KernelLaws.acceptsOrder("/Order", "../Quantity",
                "FieldFilled(Quantity) And [Quantity] / 2 + [Quantity] / 3 > 0");
    }
}
