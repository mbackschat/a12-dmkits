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
 * covered its *admission* only, never its expansion kind class. Measuring it found the published line
 * <b>wrong on the date half</b>: {@code DATE} and {@code DATE_FRAGMENT} are NOT compatible here.
 *
 * <p>Every row is a complete emitted code list, and each refusal is paired with the homogeneous control that
 * attributes it to the operand list rather than to the comparison — {@link #theDateFamilyIsNotOne} is the pair
 * that carries the correction, because a mixed list and a same-shape unmixed list differ only in the kinds.
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
                    .dateFragmentField("SeasonMonth", f -> f.fragmentFormat("MM")))
            .build().toJson();

    // ── the correction: the date family is not a family ─────────────────────────────────────────────

    /**
     * THE measurement that corrects the catalog. The first two rows have the same list shape, the same
     * comparison, and the same error field; only the second operand's kind differs. A homogeneous DATE list
     * and a homogeneous DATE_FRAGMENT list both pass, so neither kind is individually at fault and the
     * comparison is not what is refused — the mixed list is.
     */
    @Test
    void theDateFamilyIsNotOne() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("DATE + DATE_FRAGMENT", verdict("ListedOn",
                "FirstFilledValue(ListedOn, SeasonPeriod) == Today"));
        actual.put("DATE + DATE — control", verdict("ListedOn",
                "FirstFilledValue(ListedOn, SoldOn) == Today"));
        actual.put("DATE alone in a comparison — control", verdict("ListedOn", "[ListedOn] == Today"));
        actual.put("DATE_FRAGMENT alone in a comparison — control", verdict("SeasonPeriod",
                "[SeasonPeriod] == Today"));
        // A fragment must carry a YEAR to compare with a full date at all — a month-only fragment
        // draws MVK_INVALID_COMPARE_TO_DATE, which is the comparability partition and a different
        // gate from the family one under test. Pinned so the control above cannot be read as
        // "any fragment compares to Today".
        actual.put("…and a YEARLESS fragment cannot compare to a full date at all",
                verdict("SeasonMonth", "[SeasonMonth] == Today"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("DATE + DATE_FRAGMENT", "MVK_VARYING_TYPES_NOT_ALLOWED");
        expected.put("DATE + DATE — control", "admitted");
        expected.put("DATE alone in a comparison — control", "admitted");
        expected.put("DATE_FRAGMENT alone in a comparison — control", "admitted");
        expected.put("…and a YEARLESS fragment cannot compare to a full date at all",
                "MVK_INVALID_COMPARE_TO_DATE");
        assertEquals(expected, actual,
                "DATE and DATE_FRAGMENT do NOT share an owner family in a FirstFilledValue operand list — "
                        + "the catalog claimed they did until 2026-08-15");
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
