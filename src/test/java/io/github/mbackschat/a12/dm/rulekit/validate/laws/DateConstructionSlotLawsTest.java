package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;

/**
 * KERNEL-FINDINGS §6 — what each SLOT of a {@code Date}/{@code Time} construction admits.
 *
 * <p>One construct, one checker: {@code CheckBaseDateConstructionImpl} with its two subclasses supplying the
 * per-slot bounds and the extract operator each slot expects. A slot takes one of three arms — a quoted
 * constant, a date/time EXTRACT operator, or an entity — and the arm is what picks the class, so a table
 * built on one arm says nothing about the others.
 *
 * <p>This class pins the two arms that need no declaration: the constant's range and width, and which
 * extract operator belongs in which slot. The entity arm reads declared types and is
 * {@link TemporalComponentDeclarationLawsTest}'s.
 *
 * <p>Own domain (travel booking).
 */
class DateConstructionSlotLawsTest {

    private static final String WRONG_FORMAT = "MVK_WRONG_DATE_FORMAT_FOR_OP";
    private static final String WRONG_SLOT = "MVK_INVALID_DATE_OR_TIME_EXTRACT_OP";
    private static final String WRONG_FAMILY = "MVK_DATE_OR_TIME_EXTRACT_OP_NOT_ALLOWED";

    private static final String BOOKING = DocumentModelDraft.named("booking").locale("en_US")
            .rootGroup("Booking", b -> b
                    .stringField("BookingRef", s -> { })
                    .dateField("TravelDate", d -> d.format("yyyy-MM-dd"))
                    .timeField("Departure", t -> t.format("HH:mm:ss")))
            .build().toJson();

    /** The verdict on a rule comparing one of the two temporal fields against a construction. */
    private static String verdict(String target, String construction) {
        return KernelLaws.verdictModel(BOOKING, "/Booking", "../" + target,
                "FieldFilled(" + target + ") And [" + target + "] == " + construction);
    }

    private static String date(String construction) {
        return verdict("TravelDate", construction);
    }

    private static String time(String construction) {
        return verdict("Departure", construction);
    }

    /**
     * A quoted constant against its slot's own bounds. Each row moves one component out of range and leaves
     * the other two valid, so the class cannot be attributed to the construction as a whole.
     */
    @Test
    void whatEachConstantSlotOfADateAdmits() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("a valid date — control", date("Date(\"01\", \"02\", \"2024\")"));
        actual.put("components written without a leading zero", date("Date(\"1\", \"2\", \"2024\")"));
        actual.put("day 32", date("Date(\"32\", \"02\", \"2024\")"));
        actual.put("day 0", date("Date(\"0\", \"02\", \"2024\")"));
        actual.put("month 13", date("Date(\"01\", \"13\", \"2024\")"));
        actual.put("year 1799", date("Date(\"01\", \"02\", \"1799\")"));
        actual.put("year 2200", date("Date(\"01\", \"02\", \"2200\")"));
        actual.put("year written in two digits", date("Date(\"01\", \"02\", \"24\")"));
        actual.put("a non-numeric component", date("Date(\"oh\", \"02\", \"2024\")"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("a valid date — control", KernelLaws.ADMITTED);
        expected.put("components written without a leading zero", KernelLaws.ADMITTED);
        expected.put("day 32", WRONG_FORMAT);
        expected.put("day 0", WRONG_FORMAT);
        expected.put("month 13", WRONG_FORMAT);
        expected.put("year 1799", WRONG_FORMAT);
        expected.put("year 2200", WRONG_FORMAT);
        // The year slot is the only one that also demands a WIDTH, which is what the two-digit row shows.
        expected.put("year written in two digits", WRONG_FORMAT);
        expected.put("a non-numeric component", WRONG_FORMAT);
        assertEquals(expected, actual, "each date slot's constant bounds");
    }

    @Test
    void whatEachConstantSlotOfATimeAdmits() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("a valid time — control", time("Time(\"23\", \"59\", \"59\")"));
        actual.put("midnight — control", time("Time(\"00\", \"00\", \"00\")"));
        actual.put("hour 24", time("Time(\"24\", \"00\", \"00\")"));
        actual.put("minute 60", time("Time(\"23\", \"60\", \"00\")"));
        actual.put("second 60", time("Time(\"23\", \"59\", \"60\")"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("a valid time — control", KernelLaws.ADMITTED);
        expected.put("midnight — control", KernelLaws.ADMITTED);
        expected.put("hour 24", WRONG_FORMAT);
        expected.put("minute 60", WRONG_FORMAT);
        expected.put("second 60", WRONG_FORMAT);
        assertEquals(expected, actual, "each time slot's constant bounds");
    }

    /**
     * Which extract operator a slot expects, and the SPLIT that a family reading "wrong operator" as one
     * class would miss: an operator of the construction's own family in the wrong slot is a different class
     * from one belonging to the other family.
     *
     * <p>{@code QuarterFromDate} is the discriminating row for the second class inside the DATE family: it is
     * a date extract operator, so a rule keyed on "date operator in a date construct" predicts the wrong-slot
     * class for it, and no slot expects it.
     */
    @Test
    void whichExtractOperatorEachSlotExpects() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("the operator each date slot expects — control",
                date("Date(DayFromDate(TravelDate), MonthFromDate(TravelDate), YearFromDate(TravelDate))"));
        actual.put("a date operator in the wrong date slot",
                date("Date(MonthFromDate(TravelDate), MonthFromDate(TravelDate), YearFromDate(TravelDate))"));
        actual.put("QuarterFromDate, which no slot expects",
                date("Date(QuarterFromDate(TravelDate), MonthFromDate(TravelDate), YearFromDate(TravelDate))"));
        actual.put("a TIME operator in a date slot",
                date("Date(HoursFromTime(Departure), MonthFromDate(TravelDate), YearFromDate(TravelDate))"));
        actual.put("the operator each time slot expects — control",
                time("Time(HoursFromTime(Departure), MinutesFromTime(Departure), SecondsFromTime(Departure))"));
        actual.put("a time operator in the wrong time slot",
                time("Time(MinutesFromTime(Departure), MinutesFromTime(Departure), SecondsFromTime(Departure))"));
        actual.put("a DATE operator in a time slot",
                time("Time(DayFromDate(TravelDate), MinutesFromTime(Departure), SecondsFromTime(Departure))"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("the operator each date slot expects — control", KernelLaws.ADMITTED);
        expected.put("a date operator in the wrong date slot", WRONG_SLOT);
        expected.put("QuarterFromDate, which no slot expects", WRONG_FAMILY);
        expected.put("a TIME operator in a date slot", WRONG_FAMILY);
        expected.put("the operator each time slot expects — control", KernelLaws.ADMITTED);
        expected.put("a time operator in the wrong time slot", WRONG_SLOT);
        expected.put("a DATE operator in a time slot", WRONG_FAMILY);
        assertEquals(expected, actual, "which extract operator each slot expects");
    }

    /**
     * The order between the two arms' gates, on a construction that violates both. A12 checks the slots
     * left to right, so the EARLIER slot answers whatever kind of defect each carries — which is what a
     * checker walking gates rather than slots would get wrong.
     */
    @Test
    void theEarlierSlotAnswersWhicheverArmEachCarries() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("bad constant in the day slot, bad operator in the month slot",
                date("Date(\"32\", HoursFromTime(Departure), YearFromDate(TravelDate))"));
        actual.put("bad operator in the day slot, bad constant in the month slot",
                date("Date(HoursFromTime(Departure), \"13\", \"2024\")"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("bad constant in the day slot, bad operator in the month slot", WRONG_FORMAT);
        expected.put("bad operator in the day slot, bad constant in the month slot", WRONG_FAMILY);
        assertEquals(expected, actual, "slots are answered left to right");
    }
}
