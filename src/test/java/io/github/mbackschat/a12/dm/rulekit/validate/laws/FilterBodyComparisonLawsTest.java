package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;

/**
 * KERNEL-FINDINGS KF467 — a comparison inside a {@code Having} filter body is judged as one outside it.
 *
 * <p>The body is a {@code bedingung}, so {@code checkVergleichsBedingung} runs on its comparisons as it does
 * anywhere else. Each row writes one comparison family's defining refusal — the shape its own lock measures
 * outside a filter — over fields of the filtered repetition, so the body reads only the row it filters and
 * the class, if any, is the comparison's.
 *
 * <p>Own domain (a claims policy).
 */
class FilterBodyComparisonLawsTest {

    private static final String POLICY = DocumentModelDraft.named("claims-policy").locale("en_US")
            .rootGroup("Policy", policy -> policy
                    .stringField("Reference", f -> { })
                    .group("Claims", claims -> claims.repeatable(5)
                            .stringField("ClaimNo", f -> { })
                            .stringField("Notes", f -> { })
                            .numberField("Units", n -> n.fractionDigits(0))
                            .numberField("Paid", n -> n.fractionDigits(2))
                            .enumField("Cause", e -> e.value("FIRE").value("WATER"))
                            .enumField("Tier", e -> e
                                    .value("BASIC", l -> l.label("en_US", "Basic cover"))
                                    .value("PLUS", l -> l.label("en_US", "Plus cover")))
                            // One shared stored token carrying a different text than `Tier` gives it.
                            .enumField("TierConflict", e -> e
                                    .value("BASIC", l -> l.label("en_US", "Budget cover"))
                                    .value("PLUS", l -> l.label("en_US", "Plus cover")))
                            .field("Urgent", FieldKind.BOOLEAN)
                            .field("Settled", FieldKind.CONFIRM)
                            .dateField("Reported", d -> d.format("dd.MM.yyyy"))
                            .dateField("Anniversary", d -> d.format("MM-dd"))
                            .dateRangeField("Window", d -> d.format("dd.MM.yyyy").separator("-"))
                            .dateRangeField("MonthWindow", d -> d.format("MM").separator("/"))))
            .build().toJson();

    private static String filtered(String comparison) {
        return verdict("Sum(Claims*/Paid Having " + comparison + ") > 0");
    }

    /** Names the error field, so an admitted control is admitted rather than refused for not reading it. */
    private static String verdict(String condition) {
        return KernelLaws.verdictModel(POLICY, "/Policy", "../Reference", "FieldFilled(Reference) And " + condition);
    }

    @Test
    void eachComparisonFamilysRefusalIsDrawnInsideAFilterBody() {
        Map<String, String> rows = new LinkedHashMap<>();
        rows.put("[Claims/Units] == 1", KernelLaws.ADMITTED);
        rows.put("[Claims/Notes] == [Claims/Units]", "MVK_INVALID_COMPARE_TO_ENUM_OR_STRING");
        rows.put("[Claims/Cause] == \"SMOKE\"", "MVK_INVALID_STRING_CONSTANT_FOR_ENUM_COMPARISON");
        rows.put("[Claims/Notes] == [Claims/Reported]", "MVK_INVALID_COMPARE_TO_DATE");
        rows.put("[Claims/Cause] == [Claims/Urgent]", "MVK_INVALID_COMPARE_TO_YESNO");
        rows.put("[Claims/Notes] < [Claims/Units]", "MVK_INVALID_TYPE_FOR_COMPARISON");
        rows.put("\"a\" == 1", "MVK_INVALID_COMPARE_TO_CONST");
        rows.put("[Claims/Tier] == [Claims/Notes]", "MVK_INVALID_COMPARE_ENUM_TO_STRING");
        rows.put("[Claims/Tier] == [Claims/TierConflict]", "MVK_INVALID_COMPARE_ENUM_TO_ENUM");
        rows.put("[Claims/Units] DiffersWithToleranceRange1 True", "MVK_INVALID_TYPE_FOR_TOLERANCE_OP");
        rows.put("[Claims/Units] == True", "MVK_INCONSISTENT_TYPES_COMPARED");
        rows.put("[Claims/Units] == [Claims/Paid]", "MVK_INVALID_COMPARE_DEC_PLACES");
        rows.put("[Claims/Settled] == False", "MVK_INVALID_COMPARE_TO_YES");
        rows.put("[Claims/Reported] == [Claims/Anniversary]", "MVK_INVALID_COMPARE_TO_DATE");
        rows.put("[Claims/Window] == [Claims/MonthWindow]", "MVK_INVALID_COMPARE_TO_DATE_RANGE");
        rows.put("Length(Claims/Notes) PatternMatched \"a+\"", "MVK_INVALID_PARAMETER_FOR_PATTERN_COMPARISON");
        rows.put("[Claims/Units] PatternMatched \"a+\"", "MVK_INVALID_TYPE_FOR_PATTERN_COMPARISON");
        rows.put("[Claims/Notes] PatternMatched [Claims/ClaimNo]", "MVK_MISSING_CONST_IN_PATTERN_COMP");
        rows.put("[Claims/Notes] PatternMatched \"a\\\\*+\"", "MVK_INVALID_PATTERN");

        Map<String, String> actual = new LinkedHashMap<>();
        rows.keySet().forEach(comparison -> actual.put(comparison, filtered(comparison)));
        assertEquals(rows, actual, "each comparison family's refusal, inside a filter body");
    }

    /**
     * The body reads the filtered row even where the filtered operand is itself refused, because the filter's
     * own chain answers once the whole spec is read — body included — and the body's comparison is raised as
     * soon as it is read. Each refused shape has its sound-body control beside it, so the class that loses to
     * the comparison is measured rather than assumed.
     */
    @Test
    void aFilterBodysComparisonOutranksTheFilteredSpecsOwnClass() {
        Map<String, String> rows = new LinkedHashMap<>();
        rows.put("Sum(Claims/Paid Having [Claims/Units] > 5) > 0", "MVK_NO_WILDCARD");
        rows.put("Sum(Claims/Paid Having [Claims/Notes] < [Claims/Units]) > 0", "MVK_INVALID_TYPE_FOR_COMPARISON");
        rows.put("Sum(Claims*/Paid Having Sum(Claims*/Units Having [Claims/Units] > 5) > 0) > 0",
                "MVK_DISALLOWED_CONSTRUCT_IN_FILTER_CONDITION");
        rows.put("Sum(Claims*/Paid Having Sum(Claims*/Units Having [Claims/Notes] < [Claims/Units]) > 0) > 0",
                "MVK_INVALID_TYPE_FOR_COMPARISON");
        rows.put("Sum(Claims*/Paid Having Sum(Claims*/Units Having [Reference] > 5) > 0) > 0",
                "MVK_INVALID_TYPE_FOR_COMPARISON");
        rows.put("Sum(Claims*/Paid Having Sum(Claims/Units Having [Claims/Units] > 5) > 0) > 0", "MVK_NO_WILDCARD");
        rows.put("Sum(Claims*/Paid Having Sum(Claims/Units Having [Claims/Notes] < [Claims/Units]) > 0) > 0",
                "MVK_INVALID_TYPE_FOR_COMPARISON");

        Map<String, String> actual = new LinkedHashMap<>();
        rows.keySet().forEach(condition -> actual.put(condition, verdict(condition)));
        assertEquals(rows, actual, "a filter body's comparison beside the filtered spec's own class");
    }
}
