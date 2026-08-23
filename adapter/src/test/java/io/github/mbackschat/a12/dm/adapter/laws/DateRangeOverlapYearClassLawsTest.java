package io.github.mbackschat.a12.dm.adapter.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.RangeYearInterpretation;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer;

/**
 * Behavioral law — what the DateRange overlap gates MEAN once a model passes them ([KF200]). The admission
 * half is `validate.laws.DateRangeOverlapAdmissionLawsTest`; these are the three rows that separate the
 * account from the readings a consumer would otherwise arrive at.
 *
 * <ul>
 *   <li><b>A Base Year COMPLETES a yearless operand, it does not make the comparison year-blind.</b> The
 *       completed operand occupies the base year alone, so an exact operand naming a different year is
 *       disjoint from it even when the months coincide — the row a month-vs-month comparison would fail.</li>
 *   <li><b>With no Base Year an all-yearless list is legal and compares month/day labels directly</b>, a
 *       month-only endpoint spanning day 01 through that month's yearless maximum last day, February
 *       reaching 29.</li>
 *   <li><b>Where an {@code interpretationOfYear} declaration IS admitted, each endpoint's calendar reality
 *       is checked in the year it is placed in.</b> The same stored `01.11-29.02` is valid under `TO` and
 *       formally invalid under `FROM`, because only one of the two puts the leap day in a leap year.</li>
 * </ul>
 */
class DateRangeOverlapYearClassLawsTest {

    private static final String COVER = "/Cover";
    private static final Set<String> ROW = Set.of(COVER);

    private static final String BASED = cover(2024, RangeYearInterpretation.TO);
    private static final String PLAIN = cover(null, RangeYearInterpretation.TO);
    private static final String LEAP_TO = cover(2020, RangeYearInterpretation.TO);
    private static final String LEAP_FROM = cover(2020, RangeYearInterpretation.FROM);

    private static final BoundModel M = BoundModel.ofJson(PLAIN);

    /** `DateRangesOverlap(ExactWindow, MonthWindow)` — legal only where the year classes agree or a Base Year lifts. */
    private static String exactVsMonth() {
        return RuntimeLaws.withRule(BASED, COVER, "Clash", "../Ref", "CLASH",
                new DslRenderer().render(Dsl.fieldFilled(M.field(COVER + "/Ref"))
                        .and(Dsl.dateRangesOverlap(M.dateRange(COVER + "/ExactWindow").field(),
                                M.dateRange(COVER + "/MonthWindow").field()))));
    }

    /** `DateRangesOverlap(MonthWindow, DayWindow)` on the model with NO Base Year — the all-yearless account. */
    private static String monthVsDay() {
        return RuntimeLaws.withRule(PLAIN, COVER, "Clash", "../Ref", "CLASH",
                new DslRenderer().render(Dsl.fieldFilled(M.field(COVER + "/Ref"))
                        .and(Dsl.dateRangesOverlap(M.dateRange(COVER + "/MonthWindow").field(),
                                M.dateRange(COVER + "/DayWindow").field()))));
    }

    /**
     * Every model below is spliced downstream of the consistency gate, and two of the rows assert that a rule
     * does NOT fire — which a REFUSED model would satisfy vacuously. So admission is asserted here, once, for
     * the two spliced models the rest of the class evaluates.
     */
    @Test
    void bothSplicedModelsAreKernelAdmitted() {
        assertEquals(List.of(), RuntimeLaws.consistencyMessages(exactVsMonth()),
                "the Base-Year model must be admitted for its non-firing row to mean anything");
        assertEquals(List.of(), RuntimeLaws.consistencyMessages(monthVsDay()),
                "…and so must the all-yearless one");
    }

    /**
     * The year-sensitivity row: June 2023 against a yearless June completed to the base year 2024. A
     * comparison that dropped the year would fire; completion places them in different years, so it does not.
     */
    @Test
    void aCompletedYearlessOperandOccupiesTheBaseYearAloneRatherThanEveryYear() {
        assertFalse(RuntimeLaws.fires(exactVsMonth(), "CLASH", ROW,
                Map.of(COVER + "/Ref", "C-1",
                        COVER + "/ExactWindow", "2023-06-01/2023-06-30",
                        COVER + "/MonthWindow", "06/06")),
                "the yearless operand sits in 2024 alone, so a 2023 window is disjoint from it");

        assertTrue(RuntimeLaws.fires(exactVsMonth(), "CLASH", ROW,
                Map.of(COVER + "/Ref", "C-1",
                        COVER + "/ExactWindow", "2024-06-01/2024-06-30",
                        COVER + "/MonthWindow", "06/06")),
                "the positive control: the SAME months in the base year do overlap");
    }

    /**
     * With no Base Year the labels compare directly, and a month-only endpoint spans to that month's greatest
     * possible last day — February reaching 29, which is what makes `02-29` land inside a bare `02`.
     */
    @Test
    void anAllYearlessListComparesLabelsAndAMonthEndpointReachesTheGreatestLastDay() {
        assertTrue(RuntimeLaws.fires(monthVsDay(), "CLASH", ROW,
                Map.of(COVER + "/Ref", "C-1",
                        COVER + "/MonthWindow", "02/02",
                        COVER + "/DayWindow", "02-29/02-29")),
                "February's yearless maximum last day is 29, so 02-29 is inside a bare 02");

        assertFalse(RuntimeLaws.fires(monthVsDay(), "CLASH", ROW,
                Map.of(COVER + "/Ref", "C-1",
                        COVER + "/MonthWindow", "02/02",
                        COVER + "/DayWindow", "03-01/03-01")),
                "…and the day after it is not");
    }

    /**
     * Each endpoint of an interpretation-bearing range is checked in the year it is PLACED in: `TO` puts the
     * `29.02` end in the base year 2020 (a leap year) and rolls the start back to 2019, while `FROM` puts the
     * start in 2020 and rolls the end forward to 2021, where 29 February does not exist.
     */
    @Test
    void anInterpretedEndpointIsCheckedInTheYearItIsPlacedIn() {
        Map<String, String> wrapping = Map.of(COVER + "/Ref", "C-1", COVER + "/Interpreting", "01.11-29.02");

        assertEquals(Set.of(), RuntimeLaws.firedCodes(LEAP_TO, ROW, wrapping),
                "TO places 29.02 in the leap base year 2020");
        assertEquals(Set.of("datumBereichFormatFalsch"), RuntimeLaws.firedCodes(LEAP_FROM, ROW, wrapping),
                "FROM rolls the end forward to 2021, where 29 February does not exist");
    }

    /** Two year classes, two component sets within each, and one interpretation-bearing declaration. */
    private static String cover(Integer baseYear, RangeYearInterpretation interpretation) {
        DocumentModelDraft.Builder b = DocumentModelDraft.named("cover").locale("en_US");
        if (baseYear != null) {
            b.baseYear(baseYear);
        }
        return b.rootGroup("Cover", cover -> cover
                        .stringField("Ref", f -> { })
                        .dateRangeField("ExactWindow", r -> r.format("yyyy-MM-dd").separator("/"))
                        .dateRangeField("MonthWindow", r -> r.format("MM").separator("/"))
                        .dateRangeField("DayWindow", r -> r.format("MM-dd").separator("/"))
                        .dateRangeField("Interpreting", r -> r.format("dd.MM").separator("-")
                                .interpretationOfYear(interpretation)))
                .build().toJson();
    }
}
