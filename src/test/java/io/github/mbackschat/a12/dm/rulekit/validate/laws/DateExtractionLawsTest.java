package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DatePrecision;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;

/**
 * KERNEL-FINDINGS §6 — the date-extraction / shift families accept a <b>DateTime</b> source, not only a
 * Date. The kernel's date-and-time table types {@code AddDays} and {@code Day/Month/Year/QuarterFromDate}
 * as "X can be Date or DateTime" (BA L2924-2954), so a DateTime field flows into them directly.
 *
 * <p><b>The partial-known-date precision gradient</b> (source + lock 2026-07-12, graduates IG66):
 * {@code CheckDatumExtractOpImpl} → {@code DateUtils.checkOperationConsistentToPartialKnown} rejects
 * extracting a component the field's declared {@code datePrecision} allows to be UNKNOWN, keyed on the
 * fragment (`d`/`m`/`y`), all rejects being {@code MVK_INVALID_DATE_TYPE}. A component is extractable iff it
 * is guaranteed present under the declared optionality — the full 4&times;3 matrix (op &times; precision):
 * {@code DayFromDate} rejected on DAY/MONTH/YEAR_OPTIONAL; {@code MonthFromDate}/{@code QuarterFromDate}
 * (Quarter maps to the MONTH component) accept DAY_OPTIONAL only; {@code YearFromDate} accepts
 * DAY_OPTIONAL + MONTH_OPTIONAL, rejects YEAR_OPTIONAL. (FULL fields skip the gate.)
 */
class DateExtractionLawsTest {

    /** Own-domain fixture with the three partial-known precisions (order.dm.json's ApproxDate covers only DAY_OPTIONAL). */
    private static final String DATES = DocumentModelDraft.named("dates").locale("en_US")
            .rootGroup("Record", r -> r
                    .dateField("ApproxMonth", d -> d.format("dd.MM.yyyy").datePrecision(DatePrecision.MONTH_OPTIONAL))
                    .dateField("ApproxYear", d -> d.format("dd.MM.yyyy").datePrecision(DatePrecision.YEAR_OPTIONAL)))
            .build().toJson();

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

    @Test
    void dayFromDateIsRejectedOnAPartiallyKnownDate() {
        // ApproxDate is DAY_OPTIONAL (a partially-known date); DayFromDate is never usable on one.
        KernelLaws.rejectsOrder("/Order", "../ApproxDate",
                "FieldFilled(ApproxDate) And DayFromDate(ApproxDate) > 1", "MVK_INVALID_DATE_TYPE");
    }

    @Test
    void monthYearQuarterFromDateAcceptADayOptionalDate() {
        for (String c : new String[]{"MonthFromDate(ApproxDate) == 1", "YearFromDate(ApproxDate) > 2020",
                "QuarterFromDate(ApproxDate) == 1"}) {
            KernelLaws.acceptsOrder("/Order", "../ApproxDate", "FieldFilled(ApproxDate) And " + c);
        }
    }

    @Test
    void monthOptionalDateAcceptsOnlyYearExtraction() {
        // MONTH_OPTIONAL guarantees only the year: YearFromDate accepts; Month/Quarter/Day reject.
        KernelLaws.acceptsModel(DATES, "/Record", "../ApproxMonth",
                "FieldFilled(ApproxMonth) And YearFromDate(ApproxMonth) > 2020");
        for (String c : new String[]{"MonthFromDate(ApproxMonth) == 1", "QuarterFromDate(ApproxMonth) == 1",
                "DayFromDate(ApproxMonth) > 1"}) {
            KernelLaws.rejectsModel(DATES, "/Record", "../ApproxMonth",
                    "FieldFilled(ApproxMonth) And " + c, "MVK_INVALID_DATE_TYPE");
        }
    }

    @Test
    void yearOptionalDateRejectsEveryExtraction() {
        // YEAR_OPTIONAL guarantees nothing: every component extraction is rejected.
        for (String c : new String[]{"YearFromDate(ApproxYear) > 2020", "MonthFromDate(ApproxYear) == 1",
                "QuarterFromDate(ApproxYear) == 1", "DayFromDate(ApproxYear) > 1"}) {
            KernelLaws.rejectsModel(DATES, "/Record", "../ApproxYear",
                    "FieldFilled(ApproxYear) And " + c, "MVK_INVALID_DATE_TYPE");
        }
    }

    // ── the operand-kind gate, and the two codes that place a wrong operand ────────────────────────

    /**
     * A non-temporal operand is {@code MVK_NO_DATE}, while a temporal operand of the WRONG temporal kind is
     * {@code MVK_WRONG_DATE_FORMAT_FOR_OP} — two different refusals, so "the operand is wrong" is not one
     * rule. Each row carries the admitted control that isolates the operand as the only variable; the typed
     * peer is {@code dsl.operators.DateExtractionOperandKindTest}.
     */
    @Test
    void aNonTemporalExtractionOperandIsNotADate() {
        KernelLaws.rejectsOrder("/Order", "../CustomerName",
                "FieldFilled(CustomerName) And YearFromDate(CustomerName) > 0", "MVK_NO_DATE");
        KernelLaws.acceptsOrder("/Order", "../CustomerName",
                "FieldFilled(CustomerName) And YearFromDate(OrderDate) > 0");
    }

    /**
     * A date where a TIME is required is a format refusal, not a "not a date" one — <b>and the variable it
     * moves is the DECLARED FORMAT, not the kind</b>. {@code OrderDate} is a DATE field declared with a date
     * format, so this row holds both fixed and cannot license a kind-keyed rule: a TIME-kind field declared
     * {@code yyyy-MM-dd} answers the date extractions and is refused {@code HoursFromTime}, measured in
     * KERNEL-FINDINGS KF211 and locked in {@code adapter.laws.TemporalDeclaredFormatGateDiffTest}. What the
     * kit can state from an operand alone is therefore only {@link #aNonTemporalExtractionOperandIsNotADate}.
     */
    @Test
    void aWrongTemporalKindIsAFormatRefusal() {
        KernelLaws.rejectsOrder("/Order", "../CustomerName",
                "FieldFilled(CustomerName) And HoursFromTime(OrderDate) > 0",
                "MVK_WRONG_DATE_FORMAT_FOR_OP");
        KernelLaws.acceptsOrder("/Order", "../CustomerName",
                "FieldFilled(CustomerName) And HoursFromTime(PickupTime) > 0");
    }

    /** A date RANGE is not a point in time: it carries no component to extract, under the non-temporal code. */
    @Test
    void aDateRangeExtractionOperandIsNotADate() {
        KernelLaws.rejectsOrder("/Order", "../CustomerName",
                "FieldFilled(CustomerName) And YearFromDate(CoverageWindow) > 0", "MVK_NO_DATE");
    }

    /** The split family places a non-temporal operand under the same code the extractions do. */
    @Test
    void aNonTemporalSplitOperandIsNotADate() {
        KernelLaws.rejectsOrder("/Order", "../CustomerName",
                "FieldFilled(CustomerName) And DateFromDateTime(CustomerName) == Today", "MVK_NO_DATE");
    }

    /** The date/time SPLIT operators need a complete date-time; a plain date is the same format refusal. */
    @Test
    void splittingAPlainDateIntoItsDatePartIsAFormatRefusal() {
        KernelLaws.rejectsOrder("/Order", "../CustomerName",
                "FieldFilled(CustomerName) And DateFromDateTime(OrderDate) == Today",
                "MVK_WRONG_DATE_FORMAT_FOR_OP");
        KernelLaws.acceptsOrder("/Order", "../CustomerName",
                "FieldFilled(CustomerName) And DateFromDateTime(ScheduledAt) == Today");
    }

    /** {@code FieldFilled} takes one field: a repetition list is MVK_NO_WILDCARDS_ALLOWED, plain is fine. */
    @Test
    void fieldPresenceTakesASingleFieldNotARepetitionList() {
        KernelLaws.rejectsOrder("/Order", "../CustomerName",
                "FieldFilled(CustomerName) And FieldFilled(Items*/Count)", "MVK_NO_WILDCARDS_ALLOWED");
        KernelLaws.rejectsOrder("/Order", "../CustomerName",
                "FieldFilled(CustomerName) And FieldNotFilled(Items*/Count)", "MVK_NO_WILDCARDS_ALLOWED");
        KernelLaws.acceptsOrder("/Order", "../CustomerName",
                "FieldFilled(CustomerName) And FieldFilled(ProductCode)");
    }
}
