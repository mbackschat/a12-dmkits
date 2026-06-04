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
}
