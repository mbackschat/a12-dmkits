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
                            .dateRangeField("MonthWindow", d -> d.format("MM").separator("/")))
                    .group("Insured", insured -> insured
                            .stringField("Name", f -> { })
                            .numberField("Count", n -> n.fractionDigits(0)))
                    .group("Parties", parties -> parties.repeatable(4)
                            .stringField("Name", f -> { })
                            .numberField("Count", n -> n.fractionDigits(0)))
                    .group("Orders", orders -> orders.repeatable(3).indexField("OrderNo")
                            .stringField("OrderNo", f -> { })
                            .stringField("Note", f -> { })
                            .numberField("Units", n -> n.fractionDigits(0))
                            .group("Lines", lines -> lines.repeatable(3)
                                    .numberField("Amount", n -> n.fractionDigits(2))
                                    .stringField("Memo", f -> { })
                                    .numberField("Qty", n -> n.fractionDigits(0)))))
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
     * the comparison is measured rather than assumed. A star on the filtered FIELD is the one that does not
     * lose: it is refused as the spec's path is read, ahead of the body.
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
        rows.put("Sum(Claims*/Paid* Having [Claims/Units] > 5) > 0", "MVK_INVALID_WILDCARD");
        rows.put("Sum(Claims*/Paid* Having [Claims/Notes] < [Claims/Units]) > 0", "MVK_INVALID_WILDCARD");

        Map<String, String> actual = new LinkedHashMap<>();
        rows.keySet().forEach(condition -> actual.put(condition, verdict(condition)));
        assertEquals(rows, actual, "a filter body's comparison beside the filtered spec's own class");
    }

    /**
     * Where the filter's OWN chain is raised around its body. The kind of the filtered entity is asked as the
     * parser reads the {@code Having} keyword ({@code initFilterCondition}), so a group is refused ahead of
     * anything its body holds, a refused comparison, a name resolving to nothing and a broken token alike.
     * Everything else the chain asks — the repetitions the body reads, the key's star, the key's own wildcard —
     * is asked once the whole spec is read, so the body's comparison is named first; each of those has a sound
     * body beside it, which names the chain's own class.
     */
    @Test
    void whereTheFilterChainRaisesAroundItsBody() {
        Map<String, String> rows = new LinkedHashMap<>();
        rows.put("NumberOfFilledGroups(Claims* Having [Claims/Notes] < [Claims/Units]) > 0", NO_GROUPS);
        rows.put("NumberOfFilledGroups(Claims* Having [NoSuchField] == \"x\") > 0", NO_GROUPS);
        rows.put("NumberOfFilledGroups(Claims* Having [Claims/Units] + ) > 0", NO_GROUPS);
        rows.put("Sum(Claims*/Paid For Insured Having [Insured/Name] < [Insured/Count]) > 0", NO_GROUPS);
        rows.put("Sum(Claims*/Paid Having [Parties/Name] == \"x\") > 0", "MVK_INVALID_ITERATION_IN_FILTER_CONDITION");
        rows.put("Sum(Claims*/Paid Having [Parties/Name] < [Parties/Count]) > 0", "MVK_INVALID_TYPE_FOR_COMPARISON");
        rows.put("Sum(Claims*/Paid Having [Parties/Name] == [Parties/Count]) > 0",
                "MVK_INVALID_COMPARE_TO_ENUM_OR_STRING");
        rows.put("Sum(Claims*/Paid Having [Parties/Count] == [Claims/Paid]) > 0", "MVK_INVALID_COMPARE_DEC_PLACES");
        rows.put("Sum(Orders*/Units For Parties*/Name Having [Parties/Name] == \"x\") > 0",
                "MVK_SEMANTIC_INDEX_WITH_WILDCARD");
        rows.put("Sum(Orders*/Units For Parties*/Name Having [Parties/Name] < [Parties/Count]) > 0",
                "MVK_INVALID_TYPE_FOR_COMPARISON");
        rows.put("Sum(Orders*/Units For Parties*/Name Having [Orders/Note] == \"x\") > 0",
                "MVK_INVALID_ITERATION_IN_FILTER_CONDITION");
        rows.put("Sum(Orders*/Units For Parties*/Name Having [Orders/Note] < [Orders/Units]) > 0",
                "MVK_INVALID_TYPE_FOR_COMPARISON");
        rows.put("Sum(Orders*/Units For Reference Having [Orders/Note] == \"x\") > 0", "MVK_WILDCARDS_REQUIRED");
        rows.put("Sum(Orders*/Units For Reference Having [Orders/Note] < [Orders/Units]) > 0",
                "MVK_INVALID_TYPE_FOR_COMPARISON");
        // Two nested filters over two levels, each way round: the comparison is typed whichever level it reads.
        rows.put("Sum(Orders*/Lines*/Amount Having Sum(Orders*/Units Having [Orders/Lines/Memo] < "
                + "[Orders/Lines/Qty]) > 0) > 0", "MVK_INVALID_TYPE_FOR_COMPARISON");
        rows.put("Sum(Orders*/Units Having Sum(Orders*/Lines*/Amount Having [Orders/Lines/Memo] < "
                + "[Orders/Lines/Qty]) > 0) > 0", "MVK_INVALID_TYPE_FOR_COMPARISON");
        // An aggregate in a body with no filter of its own, which the construct gate does not reach.
        rows.put("Sum(Claims*/Paid Having Sum(Claims*/Units) > 0) > 0", "MVK_NO_ITERATION_FOR_WILDCARD");

        Map<String, String> actual = new LinkedHashMap<>();
        rows.keySet().forEach(condition -> actual.put(condition, verdict(condition)));
        assertEquals(rows, actual, "where the filter chain is raised around its body");
    }

    private static final String NO_GROUPS = "MVK_NO_GROUPS_ALLOWED";
}
