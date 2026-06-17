package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import org.junit.jupiter.api.Test;

/** KERNEL-FINDINGS — aggregate/count slot laws: {@code NumberOfValueInFields} forbids listing a field
 *  more than once ({@code MVK_DUPLICATE_PARAM1} directly, {@code MVK_DUPLICATE_PARAM2} via a group). */
class AggregateSlotLawsTest {

    @Test
    void numberOfValueInFieldsRejectsDuplicateFields() {
        KernelLaws.rejectsOrder("/Order", "../Quantity",
                "FieldFilled(Quantity) And NumberOfValueInFields(0 In Quantity, Quantity) > 0",
                "MVK_DUPLICATE_PARAM1");
    }
}
