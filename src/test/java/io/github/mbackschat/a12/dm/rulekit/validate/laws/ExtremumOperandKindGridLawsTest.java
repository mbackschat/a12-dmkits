package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;

/**
 * KERNEL-FINDINGS §9 — <b>the extrema's sortable LEADER set is exactly {@code {NUMBER, DATE}}, and every
 * later slot admits {@code NUMBER} alone</b>. [KF249] measured the position law on the NUMBER/DATE pair; this
 * completes the declared-kind axis for static admission and adds the two carriers the axis was thin on.
 *
 * <p><b>The leader slot selects which GATE runs, which is why the leader set is narrower than the admitted
 * set.</b> DATE leads successfully and is refused in every later slot; ENUM, BOOLEAN, CONFIRM, DATE_RANGE,
 * CUSTOM and STRING lead nothing, so for them the two positions coincide.
 *
 * <p><b>A starred single-field operand takes the same verdict and the same code as the plain field of that
 * kind</b>, in both positions — so the gate reads the declaration and not the operand form.
 *
 * <p><b>CUSTOM is the cell that separates the extrema's kind domain from the distinct count's</b>, and it is
 * the only kind that does: refused {@code MVK_NOT_SORTABLE} at both extrema in both positions, yet admitted by
 * {@code NumberOfDifferentValues} and {@code FieldValuesNotUnique}, where DATE_RANGE is refused by all four
 * under two DIFFERENT codes. A consumer deriving either domain from the other is wrong in exactly that cell.
 *
 * <p><b>A GROUP operand's expansion supplies the leading kind, and the discriminator is a reorder.</b> Three
 * groups carry the same three kinds in three declaration orders; the reported class follows the FIRST declared
 * member at both families, which a "a group is simply not sortable" reading cannot produce. That is the
 * question a12-kernel-lean left open on {@code SPEC-2026-09-06-06}.
 *
 * <p>Own domain (an e-commerce product catalog). The CUSTOM declaration carries an unregistered type name,
 * which the static model gate accepts.
 */
class ExtremumOperandKindGridLawsTest {

    private static final String GROUP = "/Catalog";
    private static final String ERROR_REL = "../Sku";

    private static final String NOT_SORTABLE = "MVK_NOT_SORTABLE";
    private static final String DATE_LEADER = "MVK_DATE_AND_NONDATE";
    private static final String STRING_ENUM_LEADER = "MVK_STRING_ENUM_AND_NON_STRING_ENUM";
    private static final String NUMBER_LEADER = "MVK_NUMBER_AND_NON_NUMBER";
    private static final String DISTINCT_COUNT_DOMAIN = "MVK_ONLY_STRING_ENUM_NUMBER_CMP_DATE_ALLOWED";
    private static final String UNIQUENESS_DOMAIN = "MVK_ONLY_STRING_ENUM_NUMBER_DATE_ALLOWED";

    private static final String CATALOG = DocumentModelDraft.named("product-line").locale("en_US")
            .rootGroup("Catalog", c -> c
                    .stringField("Sku", s -> { })
                    .stringField("Label", s -> { })
                    .numberField("Weight", n -> n.fractionDigits(0))
                    .numberField("Weight2", n -> n.fractionDigits(0))
                    .dateField("ReleasedOn", d -> d.format("yyyy-MM-dd"))
                    .dateField("RetiredOn", d -> d.format("yyyy-MM-dd"))
                    .enumField("Grade", e -> e.value("A").value("B"))
                    .field("Discontinued", FieldKind.BOOLEAN)
                    .field("Approved", FieldKind.CONFIRM)
                    .dateRangeField("SaleWindow", r -> r.format("dd.MM.yyyy").separator("-"))
                    .dateRangeField("SaleWindow2", r -> r.format("dd.MM.yyyy").separator("-"))
                    .customField("Serial", t -> t.type("WarehouseSerial"))
                    .customField("Serial2", t -> t.type("WarehouseSerial"))
                    // one repeatable group per DECLARATION ORDER — the reorder is the discriminator
                    .group("Lines", g -> g.repeatable(20)
                            .enumField("LineGrade", e -> e.value("A").value("B"))
                            .numberField("LineQty", n -> n.fractionDigits(0))
                            .dateField("LineDate", d -> d.format("yyyy-MM-dd")))
                    .group("Bins", g -> g.repeatable(20)
                            .numberField("BinQty", n -> n.fractionDigits(0))
                            .enumField("BinGrade", e -> e.value("A").value("B"))
                            .dateField("BinDate", d -> d.format("yyyy-MM-dd")))
                    .group("Slots", g -> g.repeatable(20)
                            .dateField("SlotDate", d -> d.format("yyyy-MM-dd"))
                            .numberField("SlotQty", n -> n.fractionDigits(0)))
                    .group("Cells", g -> g.repeatable(20)
                            .numberField("CellQty", n -> n.fractionDigits(0))
                            .numberField("CellQty2", n -> n.fractionDigits(0))))
            .build().toJson();

    private static String verdict(String condition) {
        ValidationResult r = KernelLaws.checkModel(CATALOG, GROUP, ERROR_REL, "FieldFilled(Sku) And " + condition);
        return r.ok() ? KernelLaws.ADMITTED : r.diagnostics().stream()
                .map(d -> d.code().orElse("(no code: " + d.summary() + ")"))
                .collect(Collectors.joining(","));
    }

    private record Kind(String label, String field) { }

    private static final List<Kind> NON_SORTABLE_KINDS = List.of(
            new Kind("ENUM", "Grade"),
            new Kind("BOOLEAN", "Discontinued"),
            new Kind("CONFIRM", "Approved"),
            new Kind("DATE_RANGE", "SaleWindow"),
            new Kind("CUSTOM", "Serial"),
            new Kind("STRING", "Label"));

    /** The declared-kind axis, complete for static admission, at both extrema and in both positions. */
    @Test
    void theSortableLeaderSetIsNumberAndDateAndEveryLaterSlotIsNumberOnly() {
        Map<String, String> actual = new LinkedHashMap<>();
        Map<String, String> expected = new LinkedHashMap<>();
        for (String op : List.of("MinValue", "MaxValue")) {
            for (Kind kind : NON_SORTABLE_KINDS) {
                String lead = op + " leading " + kind.label();
                String later = op + " after NUMBER, " + kind.label();
                actual.put(lead, verdict(op + "(" + kind.field() + ", Weight) == 0"));
                actual.put(later, verdict(op + "(Weight, " + kind.field() + ") == 0"));
                expected.put(lead, NOT_SORTABLE);
                expected.put(later, NOT_SORTABLE);
            }
            actual.put(op + " leading DATE", verdict(op + "(ReleasedOn, RetiredOn) == [ReleasedOn]"));
            actual.put(op + " after NUMBER, DATE", verdict(op + "(Weight, ReleasedOn) == 0"));
            actual.put(op + " leading NUMBER", verdict(op + "(Weight, Weight2) == 0"));
            expected.put(op + " leading DATE", KernelLaws.ADMITTED);
            expected.put(op + " after NUMBER, DATE", NOT_SORTABLE);
            expected.put(op + " leading NUMBER", KernelLaws.ADMITTED);
        }
        assertEquals(expected, actual, "only NUMBER and DATE lead; only NUMBER follows");
    }

    /** A star is not a distinct gate — the declaration is what is read. */
    @Test
    void aStarredSingleFieldTakesThePlainFieldsVerdictInBothPositions() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("starred ENUM leading", verdict("MinValue(Lines*/LineGrade, Weight) == 0"));
        actual.put("starred ENUM after NUMBER", verdict("MinValue(Weight, Lines*/LineGrade) == 0"));
        actual.put("starred NUMBER leading", verdict("MinValue(Lines*/LineQty, Weight) == 0"));
        actual.put("starred DATE leading", verdict("MinValue(Lines*/LineDate, ReleasedOn) == [ReleasedOn]"));
        actual.put("starred DATE after NUMBER", verdict("MinValue(Weight, Lines*/LineDate) == 0"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("starred ENUM leading", NOT_SORTABLE);
        expected.put("starred ENUM after NUMBER", NOT_SORTABLE);
        expected.put("starred NUMBER leading", KernelLaws.ADMITTED);
        expected.put("starred DATE leading", KernelLaws.ADMITTED);
        expected.put("starred DATE after NUMBER", NOT_SORTABLE);
        assertEquals(expected, actual, "the starred operand takes the plain field's verdict and code");
    }

    /** CUSTOM separates the two domains; DATE_RANGE is refused by both distinct-count members, under two codes. */
    @Test
    void theExtremaKindDomainIsNotTheDistinctCountsAndCustomIsWhereTheyPartWays() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("NumberOfDifferentValues over DATE_RANGE",
                verdict("NumberOfDifferentValues(SaleWindow, SaleWindow2) == 1"));
        actual.put("FieldValuesNotUnique over DATE_RANGE", verdict("FieldValuesNotUnique(SaleWindow, SaleWindow2)"));
        actual.put("NumberOfDifferentValues over CUSTOM", verdict("NumberOfDifferentValues(Serial, Serial2) == 1"));
        actual.put("FieldValuesNotUnique over CUSTOM", verdict("FieldValuesNotUnique(Serial, Serial2)"));
        actual.put("MinValue leading CUSTOM", verdict("MinValue(Serial, Weight) == 0"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("NumberOfDifferentValues over DATE_RANGE", DISTINCT_COUNT_DOMAIN);
        expected.put("FieldValuesNotUnique over DATE_RANGE", UNIQUENESS_DOMAIN);
        expected.put("NumberOfDifferentValues over CUSTOM", KernelLaws.ADMITTED);
        expected.put("FieldValuesNotUnique over CUSTOM", KernelLaws.ADMITTED);
        expected.put("MinValue leading CUSTOM", NOT_SORTABLE);
        assertEquals(expected, actual, "the two gates hold genuinely different kind domains");
    }

    /**
     * The reorder discriminator: the same three kinds in three declaration orders. A rival reading — "a group
     * expansion is simply not a sortable/homogeneous list" — predicts one constant class per family; the
     * measured class tracks the FIRST declared member.
     */
    @Test
    void aGroupExpansionSuppliesTheLeadingKindAtBothFamilies() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("MinValue over an ENUM-first expansion", verdict("MinValue(Lines*) == 0"));
        actual.put("MinValue over a NUMBER-first expansion", verdict("MinValue(Bins*) == 0"));
        actual.put("MinValue over a DATE-first expansion", verdict("MinValue(Slots*) == 0"));
        actual.put("MinValue over an all-NUMBER expansion", verdict("MinValue(Cells*) == 0"));
        actual.put("NumberOfDifferentValues over an ENUM-first expansion",
                verdict("NumberOfDifferentValues(Lines*) == 1"));
        actual.put("NumberOfDifferentValues over a NUMBER-first expansion",
                verdict("NumberOfDifferentValues(Bins*) == 1"));
        actual.put("NumberOfDifferentValues over a DATE-first expansion",
                verdict("NumberOfDifferentValues(Slots*) == 1"));
        actual.put("NumberOfDifferentValues over an all-NUMBER expansion",
                verdict("NumberOfDifferentValues(Cells*) == 1"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("MinValue over an ENUM-first expansion", NOT_SORTABLE);
        expected.put("MinValue over a NUMBER-first expansion", NOT_SORTABLE);
        expected.put("MinValue over a DATE-first expansion", DATE_LEADER);
        expected.put("MinValue over an all-NUMBER expansion", KernelLaws.ADMITTED);
        expected.put("NumberOfDifferentValues over an ENUM-first expansion", STRING_ENUM_LEADER);
        expected.put("NumberOfDifferentValues over a NUMBER-first expansion", NUMBER_LEADER);
        expected.put("NumberOfDifferentValues over a DATE-first expansion", DATE_LEADER);
        expected.put("NumberOfDifferentValues over an all-NUMBER expansion", KernelLaws.ADMITTED);
        assertEquals(expected, actual, "the expansion's FIRST declared member fixes the class");
    }
}
