package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import org.junit.jupiter.api.Test;

/**
 * KERNEL-FINDINGS — string-coercion slot laws. The substring range {@code RangeAsString} /
 * {@code RangeAsNumber} is <b>1-based and inclusive</b>; the kernel requires {@code start >= 1} and
 * {@code start <= end}, else {@code MVK_INVALID_INTERVAL} (source: {@code CheckFeldOpMitParamsImpl},
 * {@code if (begin < 1 || begin > ende)}). This corrects the BA doc, whose <i>Background</i> prose
 * mis-states the bound as {@code k > 1 and k < l}.
 */
class StringOpLawsTest {

    @Test
    void rangeStartBelowOneIsRejected() {
        KernelLaws.rejectsOrder("/Order", "../ProductCode",
                "FieldFilled(ProductCode) And RangeAsString(ProductCode, 0, 3) == \"AB\"",
                "MVK_INVALID_INTERVAL");
    }

    @Test
    void rangeEndBeforeStartIsRejected() {
        KernelLaws.rejectsOrder("/Order", "../ProductCode",
                "FieldFilled(ProductCode) And RangeAsString(ProductCode, 3, 1) == \"AB\"",
                "MVK_INVALID_INTERVAL");
    }

    @Test
    void fieldValueAsStringRequiresANumberOperand() {
        // FieldValueAsString coerces a NUMBER's value to its string form; a string operand is rejected.
        KernelLaws.rejectsOrder("/Order", "../CustomerName",
                "FieldFilled(CustomerName) And FieldValueAsString(CustomerName) == \"x\"", "MVK_NO_NUMBER");
        KernelLaws.acceptsOrder("/Order", "../Quantity",
                "FieldFilled(Quantity) And FieldValueAsString(Quantity) == \"0\"");
    }

    @Test
    void startEqualToEndIsAccepted() {
        // begin == ende is allowed (the reject is begin > ende) — a single-character substring.
        KernelLaws.acceptsOrder("/Order", "../ProductCode",
                "FieldFilled(ProductCode) And RangeAsString(ProductCode, 2, 2) == \"A\"");
    }
}
