package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import io.github.mbackschat.a12.dm.ModelDrafts;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.DateRangeOperand;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.Rule;
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity;
import org.junit.jupiter.api.Test;

/**
 * KERNEL-FINDINGS §6 — the <b>date-range comparability partition</b>. Each {@code DateRange(start, finish)}
 * construction passing its own endpoint-component gate is not enough: {@code ==} and {@code !=} additionally
 * require BOTH range operands to expose the <b>same declared date-component set</b>. A mismatch is refused
 * before evaluation, in either authored order (SPEC-2026-08-19-01).
 *
 * <p>Source: {@code CheckVergleichsBedingungImpl.checkDateRangeType} —
 * {@code rt1.getDateFormat().hasSameComponents(rt2.getDateFormat(), false)} → {@code MVK_INVALID_COMPARE_TO_DATE_RANGE}.
 * The {@code false} is the kernel's own: base-year completion is deliberately excluded from this gate, so
 * Base-Year-dependent compatibility is not what it decides.
 */
class DateRangeComparabilityLawsTest {

    private static final String PROFILES = ModelDrafts.dateRangeProfiles().toJson();
    private static final BoundModel M = BoundModel.ofJson(PROFILES);

    private static final DateRangeOperand YEAR_CONSTRUCTION =
            Dsl.dateRange(M.dateFragment("/Cover/FromYear"), M.dateFragment("/Cover/ToYear"));
    private static final DateRangeOperand DATE_CONSTRUCTION =
            Dsl.dateRange(M.date("/Cover/FromDate"), M.date("/Cover/ToDate"));

    private static Rule rule(String errorField, Condition condition) {
        return M.rule(M.group("/Cover"))
                .name("RangeCompare")
                .errorField(M.dateRange(errorField).field())
                .when(condition)
                .code("RANGE_COMPARE")
                .severity(Severity.ERROR)
                .message(Dsl.en("Range comparability probe."))
                .build();
    }

    @Test
    void sameComponentSetConstructionEqualityIsAccepted() {
        KernelLaws.acceptsModel(PROFILES,
                rule("/Cover/YearWindow", YEAR_CONSTRUCTION.eq(M.dateRange("/Cover/YearWindow"))));
    }

    @Test
    void sameComponentSetConstructionInequalityIsAccepted() {
        KernelLaws.acceptsModel(PROFILES,
                rule("/Cover/DateWindow", M.dateRange("/Cover/DateWindow").ne(DATE_CONSTRUCTION)));
    }

    /** A DATE endpoint and a DATE_FRAGMENT endpoint may share one construction while their components match. */
    @Test
    void mixedDateAndFragmentEndpointsWithMatchingComponentsAreAccepted() {
        KernelLaws.acceptsModel(PROFILES,
                rule("/Cover/YearWindow",
                        Dsl.dateRange(M.dateFragment("/Cover/FromYear"), M.date("/Cover/ToYearAsDate"))
                                .eq(M.dateRange("/Cover/YearWindow"))));
    }

    @Test
    void aYearConstructionAgainstAFullDateRangeIsRejected() {
        KernelLaws.rejectsModel(PROFILES,
                rule("/Cover/DateWindow", YEAR_CONSTRUCTION.eq(M.dateRange("/Cover/DateWindow"))),
                "MVK_INVALID_COMPARE_TO_DATE_RANGE");
    }

    /** The gate reads both operands, so swapping the authored sides does not admit the same mismatch. */
    @Test
    void aFullDateRangeAgainstAYearConstructionIsRejected() {
        KernelLaws.rejectsModel(PROFILES,
                rule("/Cover/DateWindow", M.dateRange("/Cover/DateWindow").ne(YEAR_CONSTRUCTION)),
                "MVK_INVALID_COMPARE_TO_DATE_RANGE");
    }

    /** Two STORED ranges of different profiles draw the same gate — it is about the operands, not construction. */
    @Test
    void twoStoredRangesOfDifferentProfilesAreRejected() {
        KernelLaws.rejectsModel(PROFILES,
                rule("/Cover/DateWindow",
                        M.dateRange("/Cover/YearWindow").eq(M.dateRange("/Cover/DateWindow"))),
                "MVK_INVALID_COMPARE_TO_DATE_RANGE");
    }
}
