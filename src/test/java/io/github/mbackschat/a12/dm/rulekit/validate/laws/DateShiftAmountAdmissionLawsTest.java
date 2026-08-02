package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import org.junit.jupiter.api.Test;

/** Kernel-admission oracle for every published date-shift entry point's signed fractional amount. */
class DateShiftAmountAdmissionLawsTest {

    @Test
    void everyPublishedShiftAcceptsAFractionalSignedAmount() {
        for (String condition : new String[] {
                "[DeliveryDate] > AddDays(OrderDate, -1.9)",
                "[DeliveryDate] > AddMonths(OrderDate, -1.9)",
                "[DeliveryDate] > AddYears(OrderDate, -1.9)",
                "[CompletedAt] > AddHours(ScheduledAt, -1.9)",
                "[CompletedAt] > AddMinutes(ScheduledAt, -1.9)",
                "[CompletedAt] > AddSeconds(ScheduledAt, -1.9)",
        }) {
            KernelLaws.acceptsOrder("/Order", "../CustomerName",
                    "FieldFilled(CustomerName) And " + condition);
        }
    }
}
