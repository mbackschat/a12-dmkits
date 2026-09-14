package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DatePrecision;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;

/**
 * KERNEL-FINDINGS §6 — <b>the six date-arithmetic operators ask FOUR questions of their date operand, and
 * which one answers depends on the COMPONENT the operator adds</b>. {@code CheckDatumAddOpImpl.check} runs
 * them in a fixed order over one operand, so a format that fails two of them draws the earlier class.
 *
 * <p><b>The first question is the operand's own declared COMPONENT</b> — {@code AddDays} demands a day,
 * {@code AddMonths} a month, {@code AddYears} a year, and the three clock operators their own unit. A format
 * that lacks it is {@code MVK_WRONG_DATE_FORMAT_FOR_OP}, and that gate runs first, which is why a pure clock
 * operand under {@code AddDays} draws this class rather than the time one below.
 *
 * <p><b>{@code MVK_INVALID_TIME} is reachable only through the CLOCK operators</b>, and that is the finding a
 * reader of the source order would otherwise get backwards. The gate demands that the format carry some date
 * component at all, but it sits BEHIND the component gate — so for the three date operators any format
 * reaching it already carries one, and only {@code AddHours}/{@code AddMinutes}/{@code AddSeconds} over a
 * pure {@code TimeType} can arrive with none.
 *
 * <p><b>{@code AddMonths} and {@code AddYears} refuse a stamp outright</b> ({@code A12K-620} in the kernel
 * source: the desired behaviour over a datetime was never settled), while {@code AddDays} and the three clock
 * operators accept the same field. The pair is the discriminator against reading the class as "the format
 * lacks the component" — here the format has it and is refused anyway.
 *
 * <p><b>A partially known date is refused before any of that</b>, {@code MVK_INVALID_DATE_TYPE}, at every
 * precision and for every operator, because the operand is read as a field spec ahead of the format gates.
 *
 * <p><b>The amount operand is typed last</b>, {@code MVK_NON_NUMERIC} — so a model that gets both operands
 * wrong reports the date one, and the amount gate is only visible once the date operand is clean.
 *
 * <p>Own domain (an insurance claim intake).
 */
class DateArithmeticOperandLawsTest {

    private static final String GROUP = "/Claim";
    private static final String ERROR_REL = "../Reference";

    private static final String WRONG_FORMAT = "MVK_WRONG_DATE_FORMAT_FOR_OP";
    private static final String INVALID_TIME = "MVK_INVALID_TIME";
    private static final String NON_NUMERIC = "MVK_NON_NUMERIC";
    private static final String PARTIAL_DATE = "MVK_INVALID_DATE_TYPE";

    private static final String CLAIM = DocumentModelDraft.named("claim-schedule").locale("en_US")
            .rootGroup("Claim", claim -> claim
                    .stringField("Reference", f -> { })
                    .numberField("Days", f -> f.fractionDigits(0))
                    .dateField("FiledOn", f -> f.format("yyyy-MM-dd"))
                    .dateField("ClosedOn", f -> f.format("yyyy-MM-dd"))
                    .dateFragmentField("CoverMonth", f -> f.fragmentFormat("yyyy-MM"))
                    .dateFragmentField("SeasonDay", f -> f.fragmentFormat("MM-dd"))
                    .dateFragmentField("CoverYear", f -> f.fragmentFormat("yyyy"))
                    .timeField("CallStart", f -> f.format("HH:mm:ss"))
                    .timeField("CallEnd", f -> f.format("HH:mm:ss"))
                    .dateTimeField("LoggedAt", f -> f.format("yyyy-MM-dd'T'HH:mm:ss"))
                    .dateTimeField("ClearedAt", f -> f.format("yyyy-MM-dd'T'HH:mm:ss"))
                    .dateField("OnsetOn", f -> f.format("yyyy-MM-dd").datePrecision(DatePrecision.MONTH_OPTIONAL)))
            .build().toJson();

    private static String verdict(String condition) {
        ValidationResult r = KernelLaws.checkModel(CLAIM, GROUP, ERROR_REL, "FieldFilled(Reference) And " + condition);
        return r.ok() ? KernelLaws.ADMITTED : r.diagnostics().stream()
                .map(d -> d.code().orElse("(no code: " + d.summary() + ")"))
                .collect(Collectors.joining(","));
    }

    /** A declared field, and a comparand of the class the arithmetic over it returns. */
    private record Operand(String label, String field, String comparand) { }

    private static final Operand DATE = new Operand("DATE yyyy-MM-dd", "FiledOn", "[ClosedOn]");
    private static final Operand YEAR_MONTH = new Operand("FRAGMENT yyyy-MM", "CoverMonth", "[CoverMonth]");
    private static final Operand MONTH_DAY = new Operand("FRAGMENT MM-dd", "SeasonDay", "[SeasonDay]");
    private static final Operand YEAR = new Operand("FRAGMENT yyyy", "CoverYear", "[CoverYear]");
    private static final Operand CLOCK = new Operand("TIME HH:mm:ss", "CallStart", "[CallEnd]");
    private static final Operand STAMP = new Operand("DATETIME", "LoggedAt", "[ClearedAt]");

    private static final List<Operand> OPERANDS = List.of(DATE, YEAR_MONTH, MONTH_DAY, YEAR, CLOCK, STAMP);

    /**
     * The component gate, over every date operator and every declared temporal shape. The operator's own unit
     * is the whole question, and the stamp column is where the A12K-620 refusal separates the two months-and-up
     * operators from the day one.
     */
    @Test
    void eachDateOperatorDemandsItsOwnComponentAndTheStampSplitsThem() {
        Map<String, String> actual = new LinkedHashMap<>();
        Map<String, String> expected = new LinkedHashMap<>();
        Map<String, String> unitOf = Map.of("AddDays", "DAY", "AddMonths", "MONTH", "AddYears", "YEAR");
        Map<String, List<String>> carries = Map.of(
                DATE.label(), List.of("DAY", "MONTH", "YEAR"),
                YEAR_MONTH.label(), List.of("MONTH", "YEAR"),
                MONTH_DAY.label(), List.of("DAY", "MONTH"),
                YEAR.label(), List.of("YEAR"),
                CLOCK.label(), List.of(),
                STAMP.label(), List.of("DAY", "MONTH", "YEAR"));
        for (String op : List.of("AddDays", "AddMonths", "AddYears")) {
            for (Operand operand : OPERANDS) {
                String cell = op + " over a " + operand.label();
                actual.put(cell, verdict(operand.comparand() + " > " + op + "(" + operand.field() + ", 1)"));
                boolean hasUnit = carries.get(operand.label()).contains(unitOf.get(op));
                boolean stampRefused = operand == STAMP && !op.equals("AddDays");
                expected.put(cell, !hasUnit || stampRefused ? WRONG_FORMAT : KernelLaws.ADMITTED);
            }
        }
        assertEquals(expected, actual, "the operator's own component decides, and a stamp is refused above days");
    }

    /**
     * The clock operators, which are the only route to {@code MVK_INVALID_TIME}: the component gate ahead of
     * it passes on a pure clock format, and the date-specification gate then refuses it.
     */
    @Test
    void theClockOperatorsAreTheOnlyRouteToTheTimeClass() {
        Map<String, String> actual = new LinkedHashMap<>();
        Map<String, String> expected = new LinkedHashMap<>();
        for (String op : List.of("AddHours", "AddMinutes", "AddSeconds")) {
            String clock = op + " over a pure clock";
            String stamp = op + " over a stamp";
            String date = op + " over a plain date";
            actual.put(clock, verdict("[CallEnd] > " + op + "(CallStart, 1)"));
            actual.put(stamp, verdict("[ClearedAt] > " + op + "(LoggedAt, 1)"));
            actual.put(date, verdict("[ClosedOn] > " + op + "(FiledOn, 1)"));
            expected.put(clock, INVALID_TIME);
            expected.put(stamp, KernelLaws.ADMITTED);
            expected.put(date, WRONG_FORMAT);
        }
        assertEquals(expected, actual, "a pure clock clears the component gate and is refused by the next one");
    }

    /** A partial precision is refused ahead of every format question, at every operator. */
    @Test
    void aPartiallyKnownDateIsRefusedBeforeTheFormatGates() {
        Map<String, String> actual = new LinkedHashMap<>();
        Map<String, String> expected = new LinkedHashMap<>();
        for (String op : List.of("AddDays", "AddMonths", "AddYears")) {
            String cell = op + " over a month-optional date";
            actual.put(cell, verdict("[ClosedOn] > " + op + "(OnsetOn, 1)"));
            expected.put(cell, PARTIAL_DATE);
        }
        // The control: the same declaration without the precision is admitted by all three.
        actual.put("the full-precision control", verdict("[ClosedOn] > AddMonths(FiledOn, 1)"));
        expected.put("the full-precision control", KernelLaws.ADMITTED);
        assertEquals(expected, actual, "the precision is read off the field spec ahead of its format");
    }

    /**
     * The amount slot, typed last — so a model with both operands wrong reports the DATE one, which is what
     * separates this gate's position from its class.
     */
    @Test
    void theAmountSlotIsTypedLastAndTakesNumbersOnly() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("a text amount", verdict("[ClosedOn] > AddDays(FiledOn, [Reference])"));
        actual.put("a date amount", verdict("[ClosedOn] > AddDays(FiledOn, [ClosedOn])"));
        actual.put("a number field amount", verdict("[ClosedOn] > AddDays(FiledOn, [Days])"));
        actual.put("a computed number amount", verdict("[ClosedOn] > AddDays(FiledOn, {[Days] + 1})"));
        actual.put("both operands wrong", verdict("[CoverYear] > AddDays(CoverYear, [Reference])"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("a text amount", NON_NUMERIC);
        expected.put("a date amount", NON_NUMERIC);
        expected.put("a number field amount", KernelLaws.ADMITTED);
        expected.put("a computed number amount", KernelLaws.ADMITTED);
        expected.put("both operands wrong", WRONG_FORMAT);
        assertEquals(expected, actual, "the amount is typed after the date operand has passed every gate");
    }
}
