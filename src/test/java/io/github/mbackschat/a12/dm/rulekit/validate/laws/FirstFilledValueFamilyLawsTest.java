package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;

/**
 * KERNEL-FINDINGS §1 — <b>which value kinds may share one {@code FirstFilledValue} operand list</b>.
 *
 * <p><b>Why this file exists.</b> Our operator catalog published the partition as a {@code constraints} line —
 * "all expanded values must share one owner family (STRING/CUSTOM/ENUMERATION or DATE/DATE_FRAGMENT are
 * compatible families)" — and `dmtool operators FirstFilledValue` serves it to any agent that asks. It was
 * prose, not a measured row: a12-kernel-lean correctly pointed out that our claim of having closed this operator
 * covered its *admission* only, never its expansion kind class. Measuring it found the published line wrong on the date half — though
 * not in the way this file first concluded: the temporal gate keys on the <b>declared format</b>, so DATE and
 * DATE_FRAGMENT DO interchange at one format, and a format mismatch is refused within a single kind.
 *
 * <p>Every row is a complete emitted code list, and each refusal is paired with the control that attributes it
 * to the operand list rather than to the comparison. {@link #theTemporalGateKeysOnTheDeclaredFormatNotTheKind}
 * carries the correction, and its own first version is the cautionary case: one row that varied kind and format
 * together looked decisive and pointed the wrong way.
 */
class FirstFilledValueFamilyLawsTest {

    /**
     * One group, one field per kind, so any pair can be put in an operand list without a second variable
     * moving. The date family needs its own builders ({@code dateField}/{@code dateFragmentField}) because a
     * kernel date-fragment carries a required fragment format — {@code FieldKind} covers only the five simple
     * kinds and is not the whole authoring surface.
     */
    private static final String MODEL = DocumentModelDraft.named("catalogue").locale("en_US")
            .rootGroup("Item", i -> i
                    .field("Title", FieldKind.STRING)
                    .field("Subtitle", FieldKind.STRING)
                    .field("Weight", FieldKind.NUMBER)
                    .field("Height", FieldKind.NUMBER)
                    .field("InStock", FieldKind.CONFIRM)
                    .enumField("Grade", e -> e.value("NEW").value("USED"))
                    .customField("StockCode", f -> f.type("SelectionCode"))
                    .dateField("ListedOn", d -> d.format("yyyy-MM-dd"))
                    .dateField("SoldOn", d -> d.format("yyyy-MM-dd"))
                    .dateFragmentField("SeasonPeriod", f -> f.fragmentFormat("yyyy-MM"))
                    .dateFragmentField("SeasonMonth", f -> f.fragmentFormat("MM"))
                    .dateField("StocktakeYear", d -> d.format("yyyy"))
                    .dateFragmentField("ReportingYear", f -> f.fragmentFormat("yyyy"))
                    .dateField("AuditedOn", d -> d.format("dd.MM.yyyy")))
            .build().toJson();

    // ── the temporal gate: declared FORMAT, not kind ───────────────────────────────────────────────

    /**
     * THE measurement, and it corrects this file's own first version.
     *
     * <p>That version asserted "DATE and DATE_FRAGMENT are not a family" from a single row comparing a
     * {@code yyyy-MM-dd} Date against a {@code yyyy-MM} DateFragment — which varies the KIND and the DECLARED
     * FORMAT together, so it cannot say which one the kernel refused. Separating them inverts the conclusion:
     * a Date declared {@code yyyy} beside a DateFragment declared {@code yyyy} is <b>admitted across two kinds</b>,
     * while two Dates differing only in declared format are <b>refused</b>. The gate reads the declared format.
     *
     * <p>This is the same rule {@link FieldValuesNotUniqueAdmissionLawsTest} already locked for its own carrier,
     * measured there against the same discriminating pair — so the two carriers agree, and a "compatible families"
     * table phrased in KINDS is wrong on both.
     */
    @Test
    void theTemporalGateKeysOnTheDeclaredFormatNotTheKind() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("same FORMAT, different KIND (DATE yyyy + FRAGMENT yyyy)", verdict("StocktakeYear",
                "FirstFilledValue(StocktakeYear, ReportingYear) == Today"));
        actual.put("same KIND, different FORMAT (DATE yyyy-MM-dd + DATE dd.MM.yyyy)", verdict("ListedOn",
                "FirstFilledValue(ListedOn, AuditedOn) == Today"));
        actual.put("same kind, same format — control", verdict("ListedOn",
                "FirstFilledValue(ListedOn, SoldOn) == Today"));
        actual.put("different kind AND different format — the confounded row", verdict("ListedOn",
                "FirstFilledValue(ListedOn, SeasonPeriod) == Today"));
        actual.put("[DATE yyyy] == Today — operand control", verdict("StocktakeYear", "[StocktakeYear] == Today"));
        actual.put("[FRAGMENT yyyy] == Today — operand control", verdict("ReportingYear",
                "[ReportingYear] == Today"));
        // A fragment must carry a YEAR to compare with a full date at all. That is the comparability
        // partition, a different gate from the one under test, pinned so the controls above cannot be
        // read as "any fragment compares to Today".
        actual.put("a YEARLESS fragment cannot compare to a full date at all", verdict("SeasonMonth",
                "[SeasonMonth] == Today"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("same FORMAT, different KIND (DATE yyyy + FRAGMENT yyyy)", "admitted");
        expected.put("same KIND, different FORMAT (DATE yyyy-MM-dd + DATE dd.MM.yyyy)",
                "MVK_VARYING_TYPES_NOT_ALLOWED");
        expected.put("same kind, same format — control", "admitted");
        expected.put("different kind AND different format — the confounded row",
                "MVK_VARYING_TYPES_NOT_ALLOWED");
        expected.put("[DATE yyyy] == Today — operand control", "admitted");
        expected.put("[FRAGMENT yyyy] == Today — operand control", "admitted");
        expected.put("a YEARLESS fragment cannot compare to a full date at all",
                "MVK_INVALID_COMPARE_TO_DATE");
        assertEquals(expected, actual,
                "the temporal gate reads the DECLARED FORMAT, not the kind — row 1 admits two kinds at one "
                        + "format, row 2 refuses one kind at two formats, and row 4 is why a single row proves "
                        + "neither");
    }

    // ── the string-side family, which the catalog had right ─────────────────────────────────────────

    @Test
    void stringCustomAndEnumerationDoShareOneFamily() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("STRING + STRING — control", verdict("Title",
                "FirstFilledValue(Title, Subtitle) == \"x\""));
        actual.put("STRING + ENUMERATION", verdict("Title", "FirstFilledValue(Title, Grade) == \"x\""));
        actual.put("STRING + CUSTOM", verdict("Title", "FirstFilledValue(Title, StockCode) == \"x\""));
        actual.put("ENUMERATION + CUSTOM", verdict("Grade", "FirstFilledValue(Grade, StockCode) == \"x\""));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("STRING + STRING — control", "admitted");
        expected.put("STRING + ENUMERATION", "admitted");
        expected.put("STRING + CUSTOM", "admitted");
        expected.put("ENUMERATION + CUSTOM", "admitted");
        assertEquals(expected, actual, "the string-side family holds as the catalog states it");
    }

    // ── the incompatible pairs, so "family" has a boundary rather than being vacuous ─────────────────

    @Test
    void crossFamilyPairsAreRefusedAndConfirmHasItsOwnClass() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("NUMBER + NUMBER — control", verdict("Weight",
                "FirstFilledValue(Weight, Height) > 0"));
        actual.put("STRING + NUMBER", verdict("Title", "FirstFilledValue(Title, Weight) == \"x\""));
        actual.put("DATE + STRING", verdict("ListedOn", "FirstFilledValue(ListedOn, Title) == Today"));
        actual.put("CONFIRM + STRING", verdict("Title", "FirstFilledValue(Title, InStock) == \"x\""));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("NUMBER + NUMBER — control", "admitted");
        expected.put("STRING + NUMBER", "MVK_VARYING_TYPES_NOT_ALLOWED");
        expected.put("DATE + STRING", "MVK_VARYING_TYPES_NOT_ALLOWED");
        expected.put("CONFIRM + STRING", "MVK_NO_BOOLY_ALLOWED");
        assertEquals(expected, actual,
                "a Confirm operand draws its own class, so the varying-types code is not a catch-all");
    }

    /** {@code admitted}, or the COMPLETE emitted code list — a partial list would hide a co-firing gate. */
    private static String verdict(String errorField, String condition) {
        ValidationResult r = KernelLaws.checkModel(MODEL, "/Item", "../" + errorField, condition);
        return r.ok() ? "admitted" : String.join(" + ", r.diagnostics().stream()
                .map(d -> d.code().orElse("(no code)")).toList());
    }
}
