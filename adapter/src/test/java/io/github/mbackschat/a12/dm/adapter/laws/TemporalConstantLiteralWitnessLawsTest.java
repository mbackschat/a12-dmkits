package io.github.mbackschat.a12.dm.adapter.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;

/**
 * KERNEL-FINDINGS §6 — which temporal <b>literal spellings</b> the kernel's lexer classifies, probed over the
 * three shapes a12-kernel-lean's {@code SPEC-2026-08-31-02} and {@code -03} record as unwitnessed. Two of the
 * three are witnessed here: the <b>omitted-year date</b> {@code "05.03."} and the <b>DATETIME</b>
 * {@code "05.03.2024T12:30:00"}. The DATERANGE stays unwitnessed over six spellings.
 *
 * <p><b>A refusal code names the check that refused, never the input that reached it.</b> a12-kernel-lean
 * withdrew one clause for reading a code the other way, and the same reading was drafted here before the
 * control below refuted it: an unclassifiable {@code "HELLO"} draws the <em>same</em> code as a well-formed
 * literal of the wrong family on every target measured. So every probe here carries that control, and the
 * only evidence a spelling classified is that it was <b>admitted</b>.
 *
 * <p><b>Two carriers for the omitted-year shape, and the carrier is not what decides it.</b> a12-kernel-lean
 * probed it as a constant assigned to a computation target and found it refused; this project's
 * {@code DateLiteral} javadoc has always offered it in a COMPARISON. Both carriers admit it here, so the
 * difference from their measurement is the <b>target's declared format</b> — their three targets all carry a
 * year and a yearless constant into one of those does need a Base Year, which is one quadrant of the grid in
 * {@link #aBaseYearIsRequiredPreciselyWhereTheConstantAndTheTargetDisagreeAboutTheYear}.
 *
 * <p><b>Raw operation and condition text on purpose.</b> {@code Dsl.dateTime} and {@code Dsl.dateRange} build
 * those kinds from constant PARTS, so the typed surface has no literal to pass — which is the gap under test.
 * Writing the token by hand is what lets the kernel's lexer, rather than our surface, answer.
 *
 * <p>Own domain (parcel fulfilment).
 */
class TemporalConstantLiteralWitnessLawsTest {

    private static final String GROUP = "/Shipment";
    private static final String TARGET = "/Shipment/Milestone";
    private static final String ADMITTED = "ADMITTED";
    private static final String CLEARED = "CLEARED";
    private static final String ERRORED = "ERRORED";
    private static final String ABSENT = "no outcome for the computed field";
    private static final Pattern CODE = Pattern.compile("\\[(MVK_[A-Z_0-9]+)]");

    /**
     * A token no lexer rule can classify. Its verdict is the <b>negative control</b> every table needs: it is
     * what "did not classify" looks like on that target, so any row sharing its code carries no information
     * about classification.
     */
    private static final String UNCLASSIFIABLE = "HELLO";

    private static final String TO_DATE = "MVK_INVALID_COMPARE_TO_DATE";
    private static final String TO_DATE_RANGE = "MVK_INVALID_COMPARE_TO_DATE_RANGE";
    private static final String FOR_COMPARISON = "MVK_INVALID_TYPE_FOR_COMPARISON";

    // ---- the omitted-year date literal (SPEC-2026-08-31-02's open request) — WITNESSED ----

    /**
     * {@code "05.03."} and {@code "13.07."} are admitted as constants; {@code "05.03"} and {@code "03.2024"}
     * are not. The trailing dot is load-bearing, which is the same "spelling, not shape" discipline the
     * complete form already shows.
     */
    @Test
    void theOmittedYearDateLiteralIsWitnessed() {
        Map<String, String> actual = new LinkedHashMap<>();
        for (String literal : List.of("05.03.", "13.07.", "05.03", "03.2024")) {
            actual.put(literal, constantAdmission(Kind.DATE, "MM-dd", literal, 2019));
        }
        actual.put("05.03.2024 — complete form, live control",
                constantAdmission(Kind.DATE, "MM-dd", "05.03.2024", 2019));
        actual.put(UNCLASSIFIABLE + " — negative control",
                constantAdmission(Kind.DATE, "MM-dd", UNCLASSIFIABLE, 2019));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("05.03.", ADMITTED);
        expected.put("13.07.", ADMITTED);
        expected.put("05.03", TO_DATE);
        expected.put("03.2024", TO_DATE);
        expected.put("05.03.2024 — complete form, live control", ADMITTED);
        expected.put(UNCLASSIFIABLE + " — negative control", TO_DATE);

        assertEquals(expected, actual,
                "the two refused spellings share the negative control's code, so this table witnesses the two "
                        + "admitted ones and says nothing about why the others failed");
    }

    /**
     * <b>The rule the Base Year actually follows, on the complete grid.</b> a12-kernel-lean's
     * {@code SPEC-2026-08-31-04} reads it as "a yearless FORMAT refuses a full-date constant without a Base
     * Year", which is true and is one quadrant of this table. It does not predict the other two: a yearless
     * constant into a yearless target needs no Base Year, and a yearless constant into a <em>complete</em>
     * target does.
     *
     * <p>What every cell obeys instead: <b>a Base Year is required exactly when the constant and the target
     * disagree about carrying a YEAR</b>, in either direction, and never otherwise. The year-leading targets
     * are what make that specific rather than "any component disagreement" — {@code yyyy} differs from a
     * complete constant in two components out of three and is admitted with no Base Year, while {@code MM-dd}
     * differs in one and is refused.
     *
     * <p>Two constants × seven declared formats × Base Year present or absent, with no cell omitted, because
     * an account that fits every row of a partial grid is exactly what this project keeps having to correct.
     */
    @Test
    void aBaseYearIsRequiredPreciselyWhereTheConstantAndTheTargetDisagreeAboutTheYear() {
        List<String> withYear = List.of("dd.MM.yyyy", "yyyy-MM-dd", "yyyy", "yyyy-MM", "yyyyMM");
        List<String> yearless = List.of("MM", "MM-dd");

        Map<String, String> actual = new LinkedHashMap<>();
        Map<String, String> expected = new LinkedHashMap<>();
        for (String constant : List.of("05.03.2024", "05.03.")) {
            boolean constantHasYear = constant.endsWith("2024");
            for (String format : concat(withYear, yearless)) {
                boolean agree = withYear.contains(format) == constantHasYear;
                for (Integer baseYear : new Integer[] {null, 2019}) {
                    String cell = constant + " → " + format
                            + (baseYear == null ? " · no Base Year" : " · Base Year 2019");
                    actual.put(cell, constantAdmission(Kind.DATE, format, constant, baseYear));
                    expected.put(cell, agree || baseYear != null ? ADMITTED : TO_DATE);
                }
            }
        }

        assertEquals(expected, actual,
                "the expectation is computed from the year-agreement rule alone, so any cell the rule gets "
                        + "wrong shows up as a difference rather than as a row nobody wrote down");
    }

    private static List<String> concat(List<String> first, List<String> second) {
        List<String> all = new java.util.ArrayList<>(first);
        all.addAll(second);
        return all;
    }

    /**
     * The COMPARISON carrier — the one our own {@code DateLiteral} javadoc's {@code [DispatchDate] > \"13.07.\"}
     * example lives in. It admits the same two spellings, so that claim has a basis and the carrier is not
     * what a12-kernel-lean's refusal turned on.
     */
    @Test
    void theComparisonCarrierAdmitsTheSameTwoSpellings() {
        Map<String, String> actual = new LinkedHashMap<>();
        for (String literal : List.of("05.03.", "13.07.", "05.03", "03.2024")) {
            actual.put(literal, comparisonAdmission("MM-dd", literal, 2019));
        }
        actual.put("05.03.2024 — complete form, live control",
                comparisonAdmission("MM-dd", "05.03.2024", 2019));
        actual.put(UNCLASSIFIABLE + " — negative control",
                comparisonAdmission("MM-dd", UNCLASSIFIABLE, 2019));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("05.03.", ADMITTED);
        expected.put("13.07.", ADMITTED);
        expected.put("05.03", FOR_COMPARISON);
        expected.put("03.2024", FOR_COMPARISON);
        expected.put("05.03.2024 — complete form, live control", ADMITTED);
        expected.put(UNCLASSIFIABLE + " — negative control", FOR_COMPARISON);

        assertEquals(expected, actual,
                "the comparison carrier reports a different code than the assignment carrier for the same "
                        + "refusals, which is the check differing and not the classification");
    }

    // ---- the DATETIME literal (SPEC-2026-08-31-03's open request) — WITNESSED ----

    /**
     * The DATETIME literal is the kernel's German date constant, {@code T}, and its clock constant:
     * {@code "05.03.2024T12:30:00"}. The ISO spelling the target's own declared format uses is refused, which
     * is the row that matters — the literal's spelling is a lexer convention and owes nothing to the format
     * the value will be rendered into.
     */
    @Test
    void theDateTimeLiteralIsWitnessedAsTheGermanDatePlusTPlusTheClock() {
        String isoFormat = "yyyy-MM-dd'T'HH:mm:ss";
        Map<String, String> actual = new LinkedHashMap<>();
        for (String literal : List.of("05.03.2024T12:30:00", "2024-03-05T12:30:00", "05.03.2024 12:30:00",
                "2024-03-05 12:30:00")) {
            actual.put(literal, constantAdmission(Kind.DATE_TIME, isoFormat, literal, null));
        }
        actual.put("05.03.2024 — a date literal, wrong family",
                constantAdmission(Kind.DATE_TIME, isoFormat, "05.03.2024", null));
        actual.put(UNCLASSIFIABLE + " — negative control",
                constantAdmission(Kind.DATE_TIME, isoFormat, UNCLASSIFIABLE, null));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("05.03.2024T12:30:00", ADMITTED);
        expected.put("2024-03-05T12:30:00", TO_DATE);
        expected.put("05.03.2024 12:30:00", TO_DATE);
        expected.put("2024-03-05 12:30:00", TO_DATE);
        expected.put("05.03.2024 — a date literal, wrong family", TO_DATE);
        expected.put(UNCLASSIFIABLE + " — negative control", TO_DATE);

        assertEquals(expected, actual, "one spelling of four is a DATETIME literal, and it is not the ISO one");
    }

    /** The witness stores, so it is a constant the runtime carries and not merely a token the gate tolerates. */
    @Test
    void theDateTimeWitnessStoresIntoTheTargetsDeclaredFormat() {
        assertEquals("2024-03-05T12:30:00",
                stored(Kind.DATE_TIME, "yyyy-MM-dd'T'HH:mm:ss", "05.03.2024T12:30:00", null),
                "the literal is German-spelled and the store is the declared format's — the same two-rule "
                        + "composition the date family shows");
    }

    // ---- the DATERANGE literal (SPEC-2026-08-31-03's open request) — still unwitnessed ----

    /**
     * Six spellings, none admitted — and the negative control draws the same code as all six, so this table
     * is an absence over exactly those six and carries no claim that a DATERANGE literal is unauthorable. The
     * spellings cross the two conventions the other two families use (German components, the declared
     * separator, the ISO components with the pair's own separator) plus two glue characters.
     */
    @Test
    void noDateRangeSpellingClassifiesAmongTheSixProbed() {
        Map<String, String> actual = new LinkedHashMap<>();
        for (String literal : List.of("05.03.2024-31.03.2024", "05.03.2024 - 31.03.2024",
                "05.03.2024/31.03.2024", "05.03.2024..31.03.2024", "2024-03-05-2024-03-31",
                "05.03.2024T31.03.2024")) {
            actual.put(literal, constantAdmission(Kind.DATE_RANGE, "dd.MM.yyyy", literal, null));
        }
        actual.put(UNCLASSIFIABLE + " — negative control",
                constantAdmission(Kind.DATE_RANGE, "dd.MM.yyyy", UNCLASSIFIABLE, null));
        actual.put("05.03.2024 — a date literal, wrong family",
                constantAdmission(Kind.DATE_RANGE, "dd.MM.yyyy", "05.03.2024", null));

        Map<String, String> expected = new LinkedHashMap<>();
        for (String literal : List.of("05.03.2024-31.03.2024", "05.03.2024 - 31.03.2024",
                "05.03.2024/31.03.2024", "05.03.2024..31.03.2024", "2024-03-05-2024-03-31",
                "05.03.2024T31.03.2024")) {
            expected.put(literal, TO_DATE_RANGE);
        }
        expected.put(UNCLASSIFIABLE + " — negative control", TO_DATE_RANGE);
        expected.put("05.03.2024 — a date literal, wrong family", TO_DATE);

        assertEquals(expected, actual,
                "the date-literal row draws a DIFFERENT code on the same target, which is the only reason to "
                        + "believe this gate discriminates its operand at all");
    }

    // ---- instruments ----

    private enum Kind { DATE, DATE_TIME, DATE_RANGE }

    /** The MVK gate on a model whose computation assigns {@code literal} to the target. */
    private static String constantAdmission(Kind kind, String format, String literal, Integer baseYear) {
        return verdict(constantModel(kind, format, literal, baseYear));
    }

    /** The MVK gate on a model whose rule compares the target against {@code literal}. */
    private static String comparisonAdmission(String format, String literal, Integer baseYear) {
        return verdict(RuntimeLaws.withRule(baseModel(Kind.DATE, format, baseYear), GROUP, "AfterCutoff",
                "../Milestone", "LATE", "[" + TARGET + "] > \"" + literal + "\""));
    }

    private static String verdict(String modelJson) {
        List<String> notifications = RuntimeLaws.consistencyMessages(modelJson);
        if (notifications.isEmpty()) {
            return ADMITTED;
        }
        return notifications.stream().map(TemporalConstantLiteralWitnessLawsTest::codeOf).distinct().sorted()
                .collect(Collectors.joining(", "));
    }

    private static String codeOf(String notification) {
        Matcher m = CODE.matcher(notification);
        return m.find() ? m.group(1) : notification;
    }

    /**
     * What {@code compute()} puts in the target. A model the MVK gate refuses throws rather than reporting
     * {@link #ABSENT}: an unadmitted model computes nothing, and reading that silence as a store answer would
     * make this instrument report the same thing whether the witness holds or not.
     */
    private static String stored(Kind kind, String format, String literal, Integer baseYear) {
        String modelJson = constantModel(kind, format, literal, baseYear);
        List<String> refusals = RuntimeLaws.consistencyMessages(modelJson);
        if (!refusals.isEmpty()) {
            throw new IllegalStateException("the kernel refused this model, so its silence is not a store "
                    + "reading: " + kind + " · " + format + " ← " + literal + " — " + refusals);
        }
        return RuntimeLaws.compute(modelJson, RuntimeLaws.doc().group(GROUP, 1)).stream()
                .filter(o -> TARGET.equals(o.path())).findFirst()
                .map(o -> o.errored() ? ERRORED : o.cleared() ? CLEARED : o.value().orElse(ABSENT))
                .orElse(ABSENT);
    }

    private static String constantModel(Kind kind, String format, String literal, Integer baseYear) {
        return RuntimeLaws.withComputation(baseModel(kind, format, baseYear), GROUP, "Assign", "../Milestone",
                '"' + literal + '"');
    }

    /** The target beside a String sibling, so a one-field group cannot stand in for the target itself. */
    private static String baseModel(Kind kind, String format, Integer baseYear) {
        DocumentModelDraft.Builder draft = DocumentModelDraft.named("fulfilment").locale("en_US");
        if (baseYear != null) {
            draft.baseYear(baseYear);
        }
        return draft.rootGroup("Shipment", shipment -> {
            shipment.stringField("Carrier", carrier -> { });
            switch (kind) {
                case DATE -> shipment.dateField("Milestone", f -> f.format(format));
                case DATE_TIME -> shipment.dateTimeField("Milestone", f -> f.format(format));
                case DATE_RANGE -> shipment.dateRangeField("Milestone",
                        f -> f.format(format).separator("-"));
            }
        }).build().toJson();
    }
}
