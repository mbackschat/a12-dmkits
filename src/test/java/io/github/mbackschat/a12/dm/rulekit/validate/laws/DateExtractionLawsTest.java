package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import org.junit.jupiter.api.Test;

/**
 * KERNEL-FINDINGS §6 — the date-extraction / shift families accept a <b>DateTime</b> source, not only a
 * Date. The kernel's date-and-time table types {@code AddDays} and {@code Day/Month/Year/QuarterFromDate}
 * as "X can be Date or DateTime" (BA L2924-2954), so a DateTime field flows into them directly.
 */
class DateExtractionLawsTest {

    @Test
    void fromDateExtractionsAcceptADateTimeSource() {
        // ScheduledAt is a DateTimeType field; *FromDate reads it (returning the date component's part).
        KernelLaws.acceptsOrder("/Order", "../ScheduledAt",
                "FieldFilled(ScheduledAt) And DayFromDate(ScheduledAt) > 1");
        KernelLaws.acceptsOrder("/Order", "../ScheduledAt",
                "FieldFilled(ScheduledAt) And YearFromDate(ScheduledAt) > 2020");
    }

    @Test
    void addDaysAcceptsADateTimeSource() {
        KernelLaws.acceptsOrder("/Order", "../CompletedAt",
                "AllFieldsFilled(ScheduledAt, CompletedAt) And [CompletedAt] > AddDays(ScheduledAt, 1)");
    }
}
