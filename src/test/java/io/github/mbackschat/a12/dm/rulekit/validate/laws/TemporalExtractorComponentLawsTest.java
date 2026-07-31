package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import org.junit.jupiter.api.Test;

/**
 * KERNEL-FINDINGS §6 — the EXTRACTOR component forms of {@code Date(...)} and {@code Time(...)}, and the
 * {@code TimeFromDateTime} operand gate. Closes the static halves of a12-kernel-lean SPEC-2026-07-27-12,
 * SPEC-2026-07-27-09 and SPEC-2026-07-27-07; these matrices were measured 2026-07-29 and recorded in
 * INTERPRETER-FINDINGS IF235, and are promoted here to maintained cases.
 *
 * <p>Two things make the account falsifiable rather than merely descriptive. First, the extractor's operand is
 * the <em>ordinary nonliteral temporal grammar</em>, not "a direct field": a nested shift, a dynamic
 * {@code Today}/{@code Now}, and a DateTime source all pass, while a direct temporal <em>literal</em> is
 * refused — and refused with its own code, {@code MVK_NO_CONST}. Second, the three refusal classes carry
 * <em>three different</em> codes, so position-mismatch, operator-not-allowed and four-part-form are separable
 * gates rather than one:
 *
 * <ul>
 *   <li>a mismatched but legal token at a position → {@code MVK_INVALID_DATE_OR_TIME_EXTRACT_OP};</li>
 *   <li>{@code QuarterFromDate}, which no position accepts → {@code MVK_DATE_OR_TIME_EXTRACT_OP_NOT_ALLOWED};</li>
 *   <li>any extractor in the four-part form →
 *       {@code MVK_EXTRACT_OP_NOT_ALLOWED_4_DATE_CONSTRUCT_WITH_4_PARAMS}.</li>
 * </ul>
 */
class TemporalExtractorComponentLawsTest {

    private static final String MISMATCH = "MVK_INVALID_DATE_OR_TIME_EXTRACT_OP";
    private static final String NOT_ALLOWED = "MVK_DATE_OR_TIME_EXTRACT_OP_NOT_ALLOWED";
    private static final String FOUR_PART = "MVK_EXTRACT_OP_NOT_ALLOWED_4_DATE_CONSTRUCT_WITH_4_PARAMS";
    private static final String NO_CONST = "MVK_NO_CONST";
    private static final String WRONG_FORMAT = "MVK_WRONG_DATE_FORMAT_FOR_OP";

    // --- SPEC-2026-07-27-12: Date(...) extractor components -------------------------------------

    @Test
    void eachDatePositionAdmitsItsMatchingExtractor() {
        accepts("[DeliveryDate] > Date(DayFromDate(OrderDate), MonthFromDate(OrderDate), YearFromDate(OrderDate))");
    }

    @Test
    void anExtractorMixesWithConstantAndFieldComponents() {
        accepts("[DeliveryDate] > Date(DayFromDate(OrderDate), \"01\", BirthYear)");
    }

    /** The operand is the ordinary nonliteral temporal grammar — not just a direct field. */
    @Test
    void theExtractorSourceIsAnyNonliteralTemporalExpression() {
        accepts("[DeliveryDate] > Date(DayFromDate(AddDays(OrderDate, 1)), \"01\", \"1963\")");
        accepts("[DeliveryDate] > Date(DayFromDate(Today), \"01\", \"1963\")");
        accepts("[DeliveryDate] > Date(DayFromDate(ScheduledAt), \"01\", \"1963\")");   // a DateTime source
    }

    @Test
    void aDirectTemporalLiteralInsideTheExtractorIsRefused() {
        rejects("[DeliveryDate] > Date(DayFromDate(\"01.01.2020\"), \"01\", \"1963\")", NO_CONST);
    }

    @Test
    void theThreeDateRefusalClassesCarryDistinctCodes() {
        rejects("[DeliveryDate] > Date(MonthFromDate(OrderDate), \"01\", \"1963\")", MISMATCH);
        rejects("[DeliveryDate] > Date(QuarterFromDate(OrderDate), \"01\", \"1963\")", NOT_ALLOWED);
        rejects("[DeliveryDate] > Date(\"01\", \"01\", YearFromDate(OrderDate), \"63\")", FOUR_PART);
    }

    // --- SPEC-2026-07-27-09: Time(...) extractor components -------------------------------------

    @Test
    void eachTimePositionAdmitsItsMatchingExtractor() {
        accepts("[PickupTime] > Time(HoursFromTime(PickupTime), \"30\", \"45\")");
    }

    @Test
    void theTimeExtractorSourceIsAnyNonliteralTimeBearingExpression() {
        accepts("[PickupTime] > Time(HoursFromTime(AddHours(ScheduledAt, 1)), \"30\", \"45\")");
        accepts("[PickupTime] > Time(HoursFromTime(Now), \"30\", \"45\")");             // a dynamic source
    }

    @Test
    void aMixedNumberExtractorTimePrefixIsAdmitted() {
        accepts("[PickupTime] > Time(HoursFromTime(ScheduledAt), MinutesFromTime(PickupTime), \"45\")");
    }

    @Test
    void aTimePositionMismatchAndADirectLiteralAreSeparateRefusals() {
        rejects("[PickupTime] > Time(MinutesFromTime(PickupTime), \"30\", \"45\")", MISMATCH);
        rejects("[PickupTime] > Time(HoursFromTime(\"10:30:45\"), \"30\", \"45\")", NO_CONST);
    }

    // --- SPEC-2026-07-27-07: TimeFromDateTime needs a COMPLETE DateTime operand -----------------

    @Test
    void timeFromDateTimeRequiresADateTimeOperand() {
        accepts("[PickupTime] > TimeFromDateTime(ScheduledAt)");
        rejects("[PickupTime] > TimeFromDateTime(OrderDate)", WRONG_FORMAT);    // a Date has no time part
        rejects("[PickupTime] > TimeFromDateTime(PickupTime)", WRONG_FORMAT);   // a Time has no date part
    }

    /** Its sibling takes the same operand, so the gate is the operand's completeness, not the operator. */
    @Test
    void dateFromDateTimeTakesTheSameOperand() {
        accepts("[OrderDate] > DateFromDateTime(ScheduledAt)");
    }

    /** The composition the entry names: a resolved partial Date beside a projected wall clock. */
    @Test
    void itComposesWithAResolvedPartialDate() {
        accepts("[ScheduledAt] > DateTime(ValueAsDate(ApproxDate, LastDay), TimeFromDateTime(ScheduledAt))");
    }

    /**
     * The TIME extractors' source domain, locked because the operator catalog asserts it (kf167): the operand
     * must be TIME-bearing, so dynamic {@code Now} and a datetime shift are admitted while {@code Today} — a
     * DATE — is refused by a <em>different</em> gate ({@code MVK_WRONG_DATE_FORMAT_FOR_OP}) than the literal one.
     * The catalog first claimed "Now/Today are all admitted"; N7 executes examples, not gotcha prose, so nothing
     * caught it. Three gates, three codes, one operand position.
     */
    @Test
    void theTimeExtractorSourceAdmitsNowAndShiftsButNotTheDateOnlyToday() {
        accepts("HoursFromTime(Now) > HoursFromTime(PickupTime)");
        accepts("HoursFromTime(AddHours(ScheduledAt, 1)) > HoursFromTime(PickupTime)");
        rejects("HoursFromTime(Today) > HoursFromTime(PickupTime)", WRONG_FORMAT);
        rejects("MinutesFromTime(Today) > MinutesFromTime(PickupTime)", WRONG_FORMAT);
        rejects("SecondsFromTime(Today) > SecondsFromTime(PickupTime)", WRONG_FORMAT);
        rejects("HoursFromTime(\"10:30:00\") > HoursFromTime(PickupTime)", NO_CONST);
    }

    private static void accepts(String condition) {
        KernelLaws.acceptsOrder("/Order", "../CustomerName", "FieldFilled(CustomerName) And " + condition);
    }

    private static void rejects(String condition, String code) {
        KernelLaws.rejectsOrder("/Order", "../CustomerName", "FieldFilled(CustomerName) And " + condition, code);
    }
}
