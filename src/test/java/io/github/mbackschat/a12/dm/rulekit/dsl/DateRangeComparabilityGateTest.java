package io.github.mbackschat.a12.dm.rulekit.dsl;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.ModelDrafts;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;

/**
 * The typed peer of {@code validate.laws.DateRangeComparabilityLawsTest}: {@code ==} and {@code !=} between
 * two date ranges require both operands to expose the same declared date-component SET
 * ({@code MVK_INVALID_COMPARE_TO_DATE_RANGE}).
 *
 * <p>It lives on {@link BoundModel} rather than on {@link DateRangeOperand#eq}, because the rule is
 * CONTEXTUAL — the same pair of operands is admissible or not depending on the model they were declared in —
 * and the ungated {@code eq}/{@code ne} stay for the inbound converters, whose obligation is to reconstruct a
 * kernel-accepted condition without refusing it (API-FINDINGS AF148).
 *
 * <p><b>It does NOT reuse the entity-list aggregates' component-set comparison</b>, and the base-year row is
 * why: that gate supplements every operand set with YEAR when the model declares a base year, and the kernel
 * passes {@code false} for completion here. Measured, not inferred.
 */
class DateRangeComparabilityGateTest {

    private static final BoundModel M = BoundModel.ofJson(ModelDrafts.dateRangeProfiles().toJson());

    private static final DateRangeOperand YEAR_CONSTRUCTION =
            Dsl.dateRange(M.dateFragment("/Cover/FromYear"), M.dateFragment("/Cover/ToYear"));

    private static IllegalArgumentException refused(DateRangeOperand a, DateRangeOperand b) {
        return assertThrows(IllegalArgumentException.class, () -> M.rangesEqual(a, b));
    }

    @Test
    void aMatchingComponentSetIsAdmitted() {
        assertDoesNotThrow(() -> M.rangesEqual(YEAR_CONSTRUCTION, M.dateRange("/Cover/YearWindow")));
        assertDoesNotThrow(() -> M.rangesNotEqual(M.dateRange("/Cover/DateWindow"),
                Dsl.dateRange(M.date("/Cover/FromDate"), M.date("/Cover/ToDate"))));
    }

    @Test
    void aMismatchedComponentSetIsRefusedInEitherOrder() {
        var forward = refused(YEAR_CONSTRUCTION, M.dateRange("/Cover/DateWindow"));
        assertTrue(forward.getMessage().contains("MVK_INVALID_COMPARE_TO_DATE_RANGE"), forward::getMessage);
        var reversed = refused(M.dateRange("/Cover/DateWindow"), YEAR_CONSTRUCTION);
        assertTrue(reversed.getMessage().contains("MVK_INVALID_COMPARE_TO_DATE_RANGE"), reversed::getMessage);
    }

    /** Two STORED ranges draw the same gate — it is about the operands, not about construction. */
    @Test
    void twoStoredRangesOfDifferentProfilesAreRefused() {
        refused(M.dateRange("/Cover/YearWindow"), M.dateRange("/Cover/DateWindow"));
    }

    /**
     * The kind discriminator: a DATE endpoint beside a DATE_FRAGMENT endpoint is fine while their components
     * match, so the gate compares component SETS and not declared kinds.
     */
    @Test
    void mixedEndpointKindsWithMatchingComponentsAreAdmitted() {
        assertDoesNotThrow(() -> M.rangesEqual(
                Dsl.dateRange(M.dateFragment("/Cover/FromYear"), M.date("/Cover/ToYearAsDate")),
                M.dateRange("/Cover/YearWindow")));
    }

    /** An operand with no resolvable declaration is DELEGATED, never refused — a computed range. */
    @Test
    void aComputedRangeIsDelegated() {
        DateRangeOperand computed = Dsl.firstFilledDateRange(M.flatten("/Cover/Periods*/RowMonthWindow"));
        assertDoesNotThrow(() -> M.rangesEqual(computed, M.dateRange("/Cover/DateWindow")));
    }

    /**
     * THE ROW THAT KEEPS THIS GATE DISTINCT. A declared base year does not make a yearless range comparable —
     * measured on the kernel in the laws peer. Reusing the aggregates' component-set helper, which DOES
     * supplement with YEAR, would admit this pair and be wrong.
     */
    @Test
    void aDeclaredBaseYearDoesNotAdmitAYearlessRange() {
        BoundModel withBaseYear = BoundModel.ofJson(
                DocumentModelDraft.named("range-profiles-base-year").locale("en_US").baseYear(2024)
                        .rootGroup("Cover", c -> c
                                .field("Ref", FieldKind.STRING)
                                .dateRangeField("MonthWindow", r -> r.format("MM").separator("/"))
                                .dateRangeField("YearMonthWindow", r -> r.format("yyyy-MM").separator("/")))
                        .build().toJson());
        var thrown = assertThrows(IllegalArgumentException.class,
                () -> withBaseYear.rangesEqual(withBaseYear.dateRange("/Cover/MonthWindow"),
                        withBaseYear.dateRange("/Cover/YearMonthWindow")));
        assertTrue(thrown.getMessage().contains("MVK_INVALID_COMPARE_TO_DATE_RANGE"), thrown::getMessage);
    }
}
