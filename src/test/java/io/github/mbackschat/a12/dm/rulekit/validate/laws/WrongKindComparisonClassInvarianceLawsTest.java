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
 * KERNEL-FINDINGS §11 — <b>a wrong-kind comparison reports the same class wherever it is written</b>. The
 * ladder in {@code LiteralComparisonDiagnosticLadderLawsTest} measures WHICH class; this measures that the
 * class is invariant under four things a consumer might expect to change it, and adds the three declared kinds
 * that ladder's fixture does not carry.
 *
 * <p><b>Four invariances, and the fourth is the one that makes "it is the filter's class" the wrong
 * statement.</b> The class does not follow the wrapping operator ({@code Sum}, {@code MinValue},
 * {@code NumberOfDifferentValues}, {@code AtLeastOneFieldFilled} agree); it does not follow the literal's side
 * of the comparison; it does not follow whether the comparand is a literal or a cross-kind FIELD; and it does
 * not follow the CARRIER — a {@code Having} filter leaf and a plain rule condition report the identical class
 * for the identical field kind, cell for cell. So the refusal belongs to the comparison, and the filter is not
 * a site with classes of its own.
 *
 * <p><b>The three added kinds each carry their own class</b>, which is what a table collapsing them would
 * lose: DATE_RANGE has {@code MVK_INVALID_COMPARE_TO_DATE_RANGE}; ENUM answers a String literal with
 * {@code MVK_INVALID_STRING_CONSTANT_FOR_ENUM_COMPARISON} where it answers a numeric one with the shared
 * enum-or-string class; and CUSTOM behaves as a string-like kind — admitted against a String literal, refused
 * against a numeric one.
 *
 * <p><b>Both columns are needed, and they agree on six kinds of ten.</b> They part ways on STRING, ENUM,
 * CUSTOM and NUMBER, which is exactly where the admitted cell sits in one column and a refusal in the other.
 *
 * <p><b>Every cell here writes {@code ==}, and that scope is load-bearing (stated 2026-09-07).</b> The
 * comparison OPERATOR is the axis this grid holds fixed, and it is the one that moves the class: ordering
 * reports {@code MVK_INVALID_TYPE_FOR_COMPARISON} where equality reports these kind classes, measured in
 * {@link WrongKindComparisonOperatorGateLawsTest}. Because {@code ==} lives in the two helper methods below
 * rather than in the table, it was a constant across all twenty cells and invisible from inside the set —
 * so this grid was published as though it were the whole story. a12-kernel-lean's
 * {@code SPEC-2026-09-06-04} reported the same kinds collapsing to
 * {@code MVK_INVALID_TYPE_FOR_COMPARISON}, and our answer — that the code appears in no cell — was true of
 * the column we had and false of the grid. Their column varied the operator and the literal family
 * together; ours varied only the literal family. Neither estate measured a wrong cell.
 *
 * <p>Own domain (an e-commerce product catalog).
 */
class WrongKindComparisonClassInvarianceLawsTest {

    private static final String GROUP = "/Catalog";
    private static final String ERROR_REL = "../Sku";
    private static final String NUMBER_LITERAL = "42";
    private static final String STRING_LITERAL = "\"pending\"";

    private static final String ENUM_OR_STRING = "MVK_INVALID_COMPARE_TO_ENUM_OR_STRING";
    private static final String YESNO = "MVK_INVALID_COMPARE_TO_YESNO";
    private static final String YES = "MVK_INVALID_COMPARE_TO_YES";
    private static final String DATE_RANGE = "MVK_INVALID_COMPARE_TO_DATE_RANGE";
    private static final String DATE = "MVK_INVALID_COMPARE_TO_DATE";
    private static final String ENUM_STRING_CONSTANT = "MVK_INVALID_STRING_CONSTANT_FOR_ENUM_COMPARISON";

    private static final String CATALOG = DocumentModelDraft.named("catalog-comparison").locale("en_US")
            .rootGroup("Catalog", c -> c
                    .stringField("Sku", s -> { })
                    .group("Lines", g -> g.repeatable(20)
                            .numberField("LineQty", n -> n.fractionDigits(0))
                            .stringField("LineLabel", s -> { })
                            .enumField("LineGrade", e -> e.value("A").value("B"))
                            .field("LineActive", FieldKind.BOOLEAN)
                            .field("LineApproved", FieldKind.CONFIRM)
                            .dateRangeField("LineWindow", r -> r.format("dd.MM.yyyy").separator("-"))
                            .dateField("LineDate", d -> d.format("yyyy-MM-dd"))
                            .timeField("LineClock", t -> t.format("HH:mm:ss"))
                            .dateTimeField("LineStamp", t -> t.format("yyyy-MM-dd'T'HH:mm:ss"))
                            .customField("LineSerial", t -> t.type("WarehouseSerial"))))
            .build().toJson();

    private static String verdict(String condition) {
        return classes(KernelLaws.checkModel(CATALOG, GROUP, ERROR_REL, "FieldFilled(Sku) And " + condition));
    }

    private static String classes(ValidationResult r) {
        return r.ok() ? KernelLaws.ADMITTED : r.diagnostics().stream()
                .map(d -> d.code().orElse("(no code: " + d.summary() + ")"))
                .collect(Collectors.joining(","));
    }

    private record Kind(String label, String field, String againstNumber, String againstString) { }

    private static final List<Kind> KINDS = List.of(
            new Kind("STRING", "LineLabel", ENUM_OR_STRING, KernelLaws.ADMITTED),
            new Kind("ENUM", "LineGrade", ENUM_OR_STRING, ENUM_STRING_CONSTANT),
            new Kind("BOOLEAN", "LineActive", YESNO, YESNO),
            new Kind("CONFIRM", "LineApproved", YES, YES),
            new Kind("DATE_RANGE", "LineWindow", DATE_RANGE, DATE_RANGE),
            new Kind("DATE", "LineDate", DATE, DATE),
            new Kind("TIME", "LineClock", DATE, DATE),
            new Kind("DATETIME", "LineStamp", DATE, DATE),
            new Kind("CUSTOM", "LineSerial", ENUM_OR_STRING, KernelLaws.ADMITTED),
            new Kind("NUMBER", "LineQty", KernelLaws.ADMITTED, ENUM_OR_STRING));

    /** A filter leaf inside a count, and a plain condition over the same field, under both literal families. */
    private static String inFilter(String field, String literal) {
        return verdict("NumberOfFilledFields(Lines*/LineQty Having [Lines/" + field + "] == " + literal + ") == 0");
    }

    /**
     * The same leaf, read per row by a rule that iterates {@code Lines} instead of by a filter. Holding the
     * FIELD fixed across the two carriers is what makes this a control rather than two measurements — a
     * root-level twin would move the declaration as well as the site.
     */
    private static String plain(String field, String literal) {
        return classes(KernelLaws.checkModel(CATALOG, "/Catalog/Lines", "../LineQty",
                "FieldFilled(/Catalog/Lines/LineQty) And [/Catalog/Lines/" + field + "] == " + literal));
    }

    @Test
    void theClassIsTheSameInAHavingFilterAndInAPlainCondition() {
        Map<String, String> actual = new LinkedHashMap<>();
        Map<String, String> expected = new LinkedHashMap<>();
        for (Kind kind : KINDS) {
            for (String[] column : new String[][] {
                    { "numeric literal", NUMBER_LITERAL, kind.againstNumber() },
                    { "String literal", STRING_LITERAL, kind.againstString() } }) {
                String filterRow = kind.label() + " vs a " + column[0] + " — in a Having filter";
                String plainRow = kind.label() + " vs a " + column[0] + " — in a plain condition";
                actual.put(filterRow, inFilter(kind.field(), column[1]));
                actual.put(plainRow, plain(kind.field(), column[1]));
                expected.put(filterRow, column[2]);
                expected.put(plainRow, column[2]);
            }
        }
        assertEquals(expected, actual, "the class belongs to the comparison, not to the filter");
    }

    /** The wrapping operator, the literal's side, and the comparand's form all leave the class alone. */
    @Test
    void theClassFollowsNeitherTheWrappingOperatorNorTheOperandOrderNorTheComparandsForm() {
        Map<String, String> actual = new LinkedHashMap<>();
        Map<String, String> expected = new LinkedHashMap<>();
        for (String[] wrapper : new String[][] {
                { "Sum", " == 0" }, { "MinValue", " == 0" },
                { "NumberOfDifferentValues", " == 1" }, { "AtLeastOneFieldFilled", "" } }) {
            String row = "a STRING leaf under " + wrapper[0];
            actual.put(row, verdict(wrapper[0] + "(Lines*/LineQty Having [Lines/LineLabel] == "
                    + NUMBER_LITERAL + ")" + wrapper[1]));
            expected.put(row, ENUM_OR_STRING);
            String dateRow = "a DATE leaf under " + wrapper[0];
            actual.put(dateRow, verdict(wrapper[0] + "(Lines*/LineQty Having [Lines/LineDate] == "
                    + NUMBER_LITERAL + ")" + wrapper[1]));
            expected.put(dateRow, DATE);
        }
        for (Kind kind : KINDS) {
            String leftRow = kind.label() + " with the literal on the LEFT";
            String fieldRow = kind.label() + " against a cross-kind FIELD instead of a literal";
            actual.put(leftRow, verdict("NumberOfFilledFields(Lines*/LineQty Having " + NUMBER_LITERAL
                    + " == [Lines/" + kind.field() + "]) == 0"));
            actual.put(fieldRow, verdict("NumberOfFilledFields(Lines*/LineQty Having [Lines/" + kind.field()
                    + "] == [Lines/LineQty]) == 0"));
            expected.put(leftRow, kind.againstNumber());
            expected.put(fieldRow, kind.againstNumber());
        }
        assertEquals(expected, actual, "three variables move and the class does not");
    }
}
