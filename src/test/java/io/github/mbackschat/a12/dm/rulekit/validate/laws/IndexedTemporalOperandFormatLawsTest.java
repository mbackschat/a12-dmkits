package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;

/**
 * §6 — an INDEX-SELECTED cell is gated by the declared format of the field it selects, exactly as a plain read
 * is ([KF1001](KERNEL-FINDINGS.md)). Selecting a row cannot change a declaration that belongs to the field.
 *
 * <p>Plausible is not measured, which is why this exists: the format gate on
 * {@code Dsl.dateExtraction}/{@code dateTimePart} deliberately DELEGATED an indexed operand rather than
 * assume it. The control holds the index constant and varies only the format — two fields in one indexed
 * group, one declared {@code yyyy-MM} and one {@code yyyy-MM-dd} — so the verdict cannot be attributed to the
 * indexing.
 */
class IndexedTemporalOperandFormatLawsTest {

    private static final String MODEL = DocumentModelDraft.named("indexed-temporal-format").locale("en_US")
            .rootGroup("Doc", d -> d
                    .field("Error", FieldKind.STRING)
                    .group("Rows", r -> r.repeatable(9).indexField("Key")
                            .field("Key", FieldKind.STRING)
                            .dateField("Month", f -> f.format("yyyy-MM"))
                            .dateField("Full", f -> f.format("yyyy-MM-dd"))
                            .dateField("German", f -> f.format("dd.MM.yyyy"))))
            .build().toJson();

    /** {@code <path> For "<key>"} — the index-selected read of one cell. */
    private static String indexed(String field) {
        return "/Doc/Rows/" + field + " For \"K1\"";
    }

    @Test
    void anIndexedCellIsGatedByTheSelectedFieldsDeclaredFormat() {
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("DayFromDate/Month(yyyy-MM)", "MVK_WRONG_DATE_FORMAT_FOR_OP");
        expected.put("MonthFromDate/Month(yyyy-MM)", KernelLaws.ADMITTED);
        expected.put("DayFromDate/Full(yyyy-MM-dd)", KernelLaws.ADMITTED);
        expected.put("SecondsFromTime/Full(yyyy-MM-dd)", "MVK_WRONG_DATE_FORMAT_FOR_OP");

        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("DayFromDate/Month(yyyy-MM)", verdict("DayFromDate(" + indexed("Month") + ") > 0"));
        actual.put("MonthFromDate/Month(yyyy-MM)", verdict("MonthFromDate(" + indexed("Month") + ") > 0"));
        actual.put("DayFromDate/Full(yyyy-MM-dd)", verdict("DayFromDate(" + indexed("Full") + ") > 0"));
        actual.put("SecondsFromTime/Full(yyyy-MM-dd)",
                verdict("SecondsFromTime(" + indexed("Full") + ") > 0"));

        assertEquals(expected, actual);
    }

    /** The split family reads the same declaration through the same selection. */
    @Test
    void theSplitFamilyReadsTheIndexedCellsDeclarationToo() {
        assertEquals("MVK_WRONG_DATE_FORMAT_FOR_OP",
                verdict("DateFromDateTime(" + indexed("Full") + ") == Today"));
    }

    /**
     * The entity-list aggregate gate reads an indexed operand's declaration too, so its component-set rule
     * applies through a selection exactly as through a plain reference. Measured because the aggregate gate
     * delegates an indexed operand on the same "plausible but unmeasured" grounds the extraction gate did.
     */
    @Test
    void theAggregateComponentSetGateReadsAnIndexedOperandsDeclaration() {
        assertEquals("MVK_DATEFORMATS_NOT_COMPATIBLE",
                verdict("MinValue(" + indexed("Month") + ", " + indexed("Full") + ") == Today"));
        assertEquals(KernelLaws.ADMITTED,
                verdict("MinValue(" + indexed("Full") + ", " + indexed("German") + ") == Today"),
                "equal component sets, different spellings — the set-vs-string discriminator, through a selection");
    }

    private static String verdict(String expression) {
        ValidationResult result = KernelLaws.checkModel(MODEL, "/Doc", "../Error",
                "FieldFilled(Error) And " + expression);
        return result.ok() ? KernelLaws.ADMITTED : result.diagnostics().stream()
                .map(d -> d.code().orElse("(no code: " + d.summary() + ")"))
                .distinct().sorted().collect(Collectors.joining(","));
    }
}
