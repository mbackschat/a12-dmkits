package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import io.github.mbackschat.a12.dm.ModelDrafts;
import org.junit.jupiter.api.Test;

/**
 * KERNEL-FINDINGS §6 — the <b>date-fragment comparability partition</b> (model with no base year): two
 * date(-fragment) values compare with {@code ==}/{@code !=}/ordering only if <b>both carry a year or
 * neither does</b>. A year-bearing fragment/date and a non-year fragment are incomparable. BA L4010-4074.
 *
 * <p>Source: {@code CheckVergleichsBedingungImpl.checkDateType} via
 * {@code DateFormat.formatsConsistentRegardingFormat(fmt1, fmt2, hasBaseYear())} → {@code MVK_INVALID_COMPARE_TO_DATE}.
 */
class DateFragmentComparabilityLawsTest {

    private static final String FRAGMENTS = ModelDrafts.dateFragments().toJson();

    @Test
    void sameYearClassComparisonIsAccepted() {
        KernelLaws.acceptsModel(FRAGMENTS, "/Doc", "../FullDate",
                "AllFieldsFilled(YearOnly, FullDate) And [YearOnly] == [FullDate]");
    }

    @Test
    void crossYearClassComparisonIsRejected() {
        KernelLaws.rejectsModel(FRAGMENTS, "/Doc", "../MonthOnly",
                "AllFieldsFilled(MonthOnly, YearOnly) And [MonthOnly] == [YearOnly]",
                "MVK_INVALID_COMPARE_TO_DATE");
    }
}
