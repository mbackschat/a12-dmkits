package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.ModelDrafts;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.DateRangeOperand;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.Rule;
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity;

/**
 * KERNEL-FINDINGS §6 — the constraint {@code DateRangeConstruction} names as deliberately delegated:
 * <b>endpoints whose formats carry different date components</b>.
 *
 * <p>Its sibling {@code DateRangeComparabilityLawsTest} measures the COMPARISON gate, and every row there
 * gives the construction two AGREEING endpoints while varying the stored range — so across that whole set
 * "the comparison's two operands disagree" and "the construction's own endpoints disagree" are
 * indistinguishable. They are different laws with different repair sites, which is what this file separates.
 *
 * <p>Measured: the kernel reports {@code MVK_WRONG_DATE_FORMAT_FOR_OP} <b>at the construction</b>, naming the
 * offending endpoint ("At 'DateRange': Wrong date format at 'ToDate'"), and reports it identically whatever
 * the construction is compared against — so the stored operand is not part of this verdict. That also makes
 * the code the discriminator between the two laws: the comparison gate reports
 * {@code MVK_INVALID_COMPARE_TO_DATE_RANGE}, which is asserted absent here.
 *
 * <p>The gate is CONTEXTUAL, not intrinsic: an endpoint's component set comes from the field's declared
 * format, so only a model can resolve it. {@code BoundModel.rangeComponents} yields {@code null} for a
 * disagreeing construction and therefore delegates, which is why nothing on the comparison surface catches
 * this shape.
 */
class DateRangeConstructionEndpointComponentLawsTest {

    private static final String PROFILES = ModelDrafts.dateRangeProfiles().toJson();
    private static final BoundModel M = BoundModel.ofJson(PROFILES);

    /** {@code {YEAR}} against {@code {YEAR, MONTH, DAY}} — the endpoints disagree with each other. */
    private static final DateRangeOperand MIXED_ENDPOINTS =
            Dsl.dateRange(M.dateFragment("/Cover/FromYear"), M.date("/Cover/ToDate"));

    /** Both endpoints {@code {YEAR, MONTH, DAY}} — the agreeing control. */
    private static final DateRangeOperand AGREEING_ENDPOINTS =
            Dsl.dateRange(M.date("/Cover/FromDate"), M.date("/Cover/ToDate"));

    private static Rule rule(String errorField, Condition condition) {
        return M.rule(M.group("/Cover"))
                .name("EndpointComponents")
                .errorField(M.dateRange(errorField).field())
                .when(condition)
                .code("ENDPOINT_COMPONENTS")
                .severity(Severity.ERROR)
                .message(Dsl.en("Construction endpoint component probe."))
                .build();
    }

    /** THE LIVENESS CONTROL: agreeing endpoints matching the stored range are accepted. */
    @Test
    void agreeingEndpointsMatchingTheStoredRangeAreAccepted() {
        KernelLaws.acceptsModel(PROFILES,
                rule("/Cover/DateWindow", AGREEING_ENDPOINTS.eq(M.dateRange("/Cover/DateWindow"))));
    }

    /**
     * THE DISCRIMINATOR. The construction's endpoints disagree while the stored range matches its END
     * endpoint, so a gate comparing "the construction's profile" against the stored one has nothing to refuse.
     * The comparison code is asserted absent: this is the construction's verdict, not the comparison's.
     */
    @Test
    void disagreeingEndpointsAreRefusedAtTheConstructionNotTheComparison() {
        KernelLaws.rejectsModelInsteadOf(PROFILES,
                rule("/Cover/DateWindow", MIXED_ENDPOINTS.eq(M.dateRange("/Cover/DateWindow"))),
                "MVK_WRONG_DATE_FORMAT_FOR_OP", "MVK_INVALID_COMPARE_TO_DATE_RANGE");
    }

    /**
     * The stored side is not a variable of this law: the same construction against a range matching its START
     * endpoint draws the same code. Varies the stored profile only.
     */
    @Test
    void theStoredRangeProfileDoesNotChangeTheVerdict() {
        KernelLaws.rejectsModelInsteadOf(PROFILES,
                rule("/Cover/YearWindow", MIXED_ENDPOINTS.eq(M.dateRange("/Cover/YearWindow"))),
                "MVK_WRONG_DATE_FORMAT_FOR_OP", "MVK_INVALID_COMPARE_TO_DATE_RANGE");
    }
}
