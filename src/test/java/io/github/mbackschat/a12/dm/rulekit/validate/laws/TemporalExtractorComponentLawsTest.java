package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.Fixtures;
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;

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

    /**
     * A CROSS-FAMILY token at a position is NOT the same refusal as a same-family one, and that is the whole
     * point of this row. {@code MonthFromDate} in the day slot is {@code MVK_INVALID_DATE_OR_TIME_EXTRACT_OP}
     * — right family, wrong position — while a TIME extractor in a Date slot is
     * {@code MVK_DATE_OR_TIME_EXTRACT_OP_NOT_ALLOWED}, the same code {@code QuarterFromDate} draws anywhere.
     * So the two codes partition on FAMILY ADMISSIBILITY versus POSITION, not on a single "unit must match"
     * rule — measured after the general rule was predicted and turned out wrong.
     */
    @Test
    void aCrossFamilyExtractorIsNotAdmissibleRatherThanMisplaced() {
        rejects("[DeliveryDate] > Date(HoursFromTime(PickupTime), \"01\", \"1963\")", NOT_ALLOWED);
        rejects("[PickupTime] > Time(DayFromDate(OrderDate), \"30\", \"45\")", NOT_ALLOWED);
    }

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
     * caught it. Two rejection gates with two distinct codes share this one operand position, which is why
     * coverage is keyed per extractor rather than per code — and why the acceptance half is executed for ALL
     * THREE extractors, not just the one the catalog example happens to use.
     */
    @Test
    void theTimeExtractorSourceAdmitsNowAndShiftsButNotTheDateOnlyToday() {
        for (String extractor : java.util.List.of("HoursFromTime", "MinutesFromTime", "SecondsFromTime")) {
            accepts(extractor + "(Now) > " + extractor + "(PickupTime)");
            accepts(extractor + "(AddHours(ScheduledAt, 1)) > " + extractor + "(PickupTime)");
            rejects(extractor + "(Today) > " + extractor + "(PickupTime)", WRONG_FORMAT);
            rejects(extractor + "(\"10:30:00\") > " + extractor + "(PickupTime)", NO_CONST);
        }
    }

    // --- The FOUR-COMPONENT Date form: which positions the form's own code actually covers ------

    private static final List<String> ALL_UNITS = List.of("DayFromDate", "MonthFromDate", "YearFromDate",
            "QuarterFromDate", "HoursFromTime", "MinutesFromTime", "SecondsFromTime");

    /** The four-part {@code Date(day, month, century, decade)} with one slot replaced by an extractor. */
    private static String fourPartWithExtractorAt(int slot, String unit) {
        String[] parts = {"\"01\"", "\"01\"", "\"19\"", "\"63\""};
        parts[slot] = unit + "(" + (unit.endsWith("FromTime") ? "PickupTime" : "OrderDate") + ")";
        return "Date(" + String.join(", ", parts) + ")";
    }

    /**
     * The whole four-part grid, because {@code DateConstruction} states this gate client-side and a
     * client-side refusal may be no broader than its measured basis.
     *
     * <p>The single row above measures the century slot only, and reading it as "the four-component form
     * admits no extractor in any position" is a scope transfer onto the other three — a wrong one. The
     * form's own code covers the two YEAR positions, which are the ones the form actually splits; day and
     * month keep the ordinary position and family rules, so a matching extractor there is <b>admitted</b>.
     */
    @Test
    void theFourPartFormsOwnCodeCoversTheYearPositionsOnly() {
        Map<String, String> actual = new LinkedHashMap<>();
        Map<String, String> expected = new LinkedHashMap<>();
        actual.put("liveness: four literal components",
                verdict("[DeliveryDate] > Date(\"01\", \"01\", \"19\", \"63\")"));
        expected.put("liveness: four literal components", "admitted");
        for (int slot = 0; slot < 4; slot++) {
            for (String unit : ALL_UNITS) {
                String key = unit + " @" + slot;
                actual.put(key, verdict("[DeliveryDate] > " + fourPartWithExtractorAt(slot, unit)));
                expected.put(key, fourPartExpectation(slot, unit));
            }
        }
        Assertions.assertEquals(expected, actual,
                "which positions of the four-component form the form's own code covers");
    }

    /** The rule the grid asserts, stated once so a wrong mechanism fails rather than a wrong cell. */
    private static String fourPartExpectation(int slot, String unit) {
        if (slot >= 2) {
            return FOUR_PART;
        }
        if (!DATE_POSITION_UNITS.contains(unit)) {
            return NOT_ALLOWED;
        }
        return DATE_POSITION_UNITS.indexOf(unit) == slot ? "admitted" : MISMATCH;
    }

    /** The units a Date position admits at all, in position order: day, month, year. */
    private static final List<String> DATE_POSITION_UNITS =
            List.of("DayFromDate", "MonthFromDate", "YearFromDate");

    // --- The Time(...) extractor GRID: which unit each slot admits -------------------------------

    private static final List<String> TIME_UNITS = List.of("HoursFromTime", "MinutesFromTime", "SecondsFromTime");
    private static final List<String> DATE_UNITS =
            List.of("DayFromDate", "MonthFromDate", "YearFromDate", "QuarterFromDate");

    /** {@code Time(...)} with one slot replaced by an extractor call and the rest literal. */
    private static String timeWithExtractorAt(int slot, String unit) {
        String[] parts = {"\"10\"", "\"30\"", "\"45\""};
        String source = TIME_UNITS.contains(unit) ? "PickupTime" : "OrderDate";
        parts[slot] = unit + "(" + source + ")";
        return "Time(" + String.join(", ", parts) + ")";
    }

    /**
     * The whole grid, because {@code TimeConstruction} states this gate client-side and a client-side refusal
     * may be no broader than its measured basis. The rows above measure two cells (a cross-family unit and a
     * misplaced TIME unit, both in slot 0); the position rule and the family rule are each asserted here at
     * every slot, for every unit.
     *
     * <p>The expectation is COMPUTED from the two-part rule — a TIME unit is admitted exactly at its own
     * index, and a DATE-family unit at no index — so a wrong mechanism fails rather than a wrong cell.
     */
    @Test
    void everyTimeSlotAdmitsOnlyItsOwnUnitAndNoDateFamilyUnit() {
        Map<String, String> actual = new LinkedHashMap<>();
        Map<String, String> expected = new LinkedHashMap<>();
        for (int slot = 0; slot < 3; slot++) {
            for (String unit : TIME_UNITS) {
                String key = unit + " @" + slot;
                actual.put(key, verdict("[PickupTime] > " + timeWithExtractorAt(slot, unit)));
                expected.put(key, TIME_UNITS.indexOf(unit) == slot ? "admitted" : MISMATCH);
            }
            for (String unit : DATE_UNITS) {
                String key = unit + " @" + slot;
                actual.put(key, verdict("[PickupTime] > " + timeWithExtractorAt(slot, unit)));
                expected.put(key, NOT_ALLOWED);
            }
        }
        Assertions.assertEquals(expected, actual,
                "whether a Time slot admits exactly its own unit, and the DATE family at none");
    }

    /** The verdict for a condition against the Order fixture — a code, or {@code admitted}. */
    private static String verdict(String condition) {
        ValidationResult r = KernelLaws.checkModel(Fixtures.orderJson(), "/Order", "../CustomerName",
                "FieldFilled(CustomerName) And " + condition);
        return r.ok() ? "admitted"
                : r.diagnostics().stream().map(d -> d.code().orElse("?")).findFirst().orElse("?");
    }

    private static void accepts(String condition) {
        KernelLaws.acceptsOrder("/Order", "../CustomerName", "FieldFilled(CustomerName) And " + condition);
    }

    private static void rejects(String condition, String code) {
        KernelLaws.rejectsOrder("/Order", "../CustomerName", "FieldFilled(CustomerName) And " + condition, code);
    }
}
