package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import org.junit.jupiter.api.Test;

/**
 * KERNEL-FINDINGS §6 (<a href="../../../../../../../../../docs/KERNEL-FINDINGS.md">KF88</a>, corrected
 * 2026-07-29) — {@code ValueAsDate(field, FirstDay|LastDay)} is <b>not</b> direct-comparison-only. The
 * kernel grammar gives it its own operand alternative, {@code datumAngabe2 : datumAngabe |
 * feldOpMitDatumSpecial}, and uses that alternative in exactly three places: {@code datumAddOp},
 * {@code datumDiffOp}, and the DATE argument of {@code dateTimeKonstrukt}. Every other Date position keeps
 * the narrower {@code datumAngabe} and rejects the nested form.
 *
 * <p>The discriminator that separates "admitted by placement" from "admitted at all": {@code AddHours} is a
 * {@code datumAddOp} too, so the token IS in position there — its rejection is the ordinary Date-format gate
 * ({@code MVK_WRONG_DATE_FORMAT_FOR_OP}, a Date carries no sub-day component), not a placement rejection
 * ({@code MVK_UNEXPECTED_TOKEN}). The two codes are what make the account falsifiable.
 *
 * <p>Reconciles lean SPEC-2026-07-27-02, which corrected this project's earlier "valid only as a direct
 * comparison operand" claim — an over-generalization drawn from the extractor rejection alone.
 */
class ValueAsDatePlacementLawsTest {

    private static final String PARTIAL = "ValueAsDate(ApproxDate, FirstDay)";
    private static final String PARTIAL_LAST = "ValueAsDate(ApproxDate, LastDay)";

    // --- admitted: the three datumAngabe2 positions, plus the direct comparison ------------------

    @Test
    void theDateShiftBaseAdmitsIt() {
        accepts("AddDays(" + PARTIAL_LAST + ", 1) > [OrderDate]");
        accepts("AddMonths(" + PARTIAL + ", 1) > [OrderDate]");
        accepts("AddYears(" + PARTIAL + ", 1) > [OrderDate]");
    }

    @Test
    void bothDateDifferenceOperandsAdmitIt() {
        accepts("DifferenceInDays(" + PARTIAL + ", OrderDate) > 1");
        accepts("DifferenceInDays(OrderDate, " + PARTIAL + ") > 1");
        accepts("DifferenceInMonths(" + PARTIAL + ", OrderDate) > 1");
    }

    @Test
    void theDateArgumentOfDateTimeAdmitsIt() {
        accepts("[ScheduledAt] > DateTime(" + PARTIAL + ", PickupTime)");
    }

    @Test
    void theDirectComparisonStillAdmitsIt() {
        accepts("[OrderDate] > " + PARTIAL);
    }

    // --- rejected by PLACEMENT: every position that keeps the narrower datumAngabe ---------------

    @Test
    void theTimeArgumentOfDateTimeRejectsIt() {
        rejects("[ScheduledAt] > DateTime(OrderDate, " + PARTIAL + ")", "MVK_UNEXPECTED_TOKEN");
    }

    @Test
    void everyExtractorRejectsIt() {
        rejects("DayFromDate(" + PARTIAL + ") > 1", "MVK_UNEXPECTED_TOKEN");
        rejects("YearFromDate(" + PARTIAL + ") > 1", "MVK_UNEXPECTED_TOKEN");
    }

    @Test
    void aDateConstructionComponentRejectsIt() {
        rejects("[DeliveryDate] > Date(" + PARTIAL + ", \"01\", \"1963\")", "MVK_UNEXPECTED_TOKEN");
    }

    @Test
    void aDateAggregateOperandRejectsIt() {
        rejects("[OrderDate] > Min(" + PARTIAL + ", DeliveryDate)", "MVK_UNEXPECTED_TOKEN");
    }

    // --- the discriminator: in position, refused by the ordinary format gate ---------------------

    @Test
    void aSubDayShiftIsInPositionButFailsTheDateFormatGate() {
        rejects("AddHours(" + PARTIAL + ", 1) > [ScheduledAt]", "MVK_WRONG_DATE_FORMAT_FOR_OP");
    }

    private static void accepts(String condition) {
        KernelLaws.acceptsOrder("/Order", "../CustomerName", "FieldFilled(CustomerName) And " + condition);
    }

    private static void rejects(String condition, String code) {
        KernelLaws.rejectsOrder("/Order", "../CustomerName", "FieldFilled(CustomerName) And " + condition, code);
    }
}
