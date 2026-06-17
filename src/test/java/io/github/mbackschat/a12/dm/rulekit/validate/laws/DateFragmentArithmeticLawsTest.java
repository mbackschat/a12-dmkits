package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import io.github.mbackschat.a12.dm.ModelDrafts;
import org.junit.jupiter.api.Test;

/**
 * KERNEL-FINDINGS §6 — Add* on a date fragment is gated by the fragment's granularity (BA L4076-4091):
 * days only if the format has a day, months only if it has a month, years only if it has a year.
 */
class DateFragmentArithmeticLawsTest {

    private static final String FR = ModelDrafts.dateFragments().toJson();

    @Test
    void addMonthsAcceptedOnAMonthFragment() {
        KernelLaws.acceptsModel(FR, "/Doc", "../MonthOnly",
                "FieldFilled(MonthOnly) And [MonthOnly] != AddMonths(MonthOnly, 1)");
    }

    @Test
    void addYearsAcceptedOnAYearFragment() {
        KernelLaws.acceptsModel(FR, "/Doc", "../YearOnly",
                "FieldFilled(YearOnly) And [YearOnly] != AddYears(YearOnly, 1)");
    }

    @Test
    void addDaysRejectedOnAMonthFragment() {
        // MM has no day component — DISCOVERY of the reject code.
        KernelLaws.rejectsModel(FR, "/Doc", "../MonthOnly",
                "FieldFilled(MonthOnly) And [MonthOnly] != AddDays(MonthOnly, 1)", "MVK_WRONG_DATE_FORMAT_FOR_OP");
    }
}
