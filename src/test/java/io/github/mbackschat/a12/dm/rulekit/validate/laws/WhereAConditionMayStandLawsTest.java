package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.dsl.LocalizedText;
import io.github.mbackschat.a12.dm.rulekit.validate.DraftComputation;

/**
 * KERNEL-FINDINGS KF467 — <b>a condition stands only where A12's grammar takes one</b>: at the top of the
 * text, inside {@code ( … )} there, and as a filter's body. Nowhere else.
 *
 * <p>{@code GrammarOnly_ConditionParser.g4} names {@code bedingung} in exactly three places: the entry rules,
 * {@code geklammerteBedingung : '(' bedingung ')'}, and {@code entitySpec}'s {@code 'Mit' bedingung}. Every
 * operator's argument is an {@code entitySpec}, an {@code entityListe}, an {@code operation} or a constant; an
 * {@code operation} groups with braces and has no comparison, connective or predicate in it; a
 * {@code semanticIndex} is an {@code entitySpec} or a string; a scope is an {@code entitySpec}. So a
 * comparison, a connective, a predicate call or a parenthesis anywhere but those three places is a token the
 * parser cannot use. Each row below moves ONE such construct into ONE such position, beside a control that
 * holds everything else fixed and is admitted.
 *
 * <p>Own domain (a claims policy).
 */
class WhereAConditionMayStandLawsTest {

    private static final String POLICY = DocumentModelDraft.named("claims-policy").locale("en_US")
            .rootGroup("Policy", policy -> policy
                    .stringField("Reference", f -> { })
                    .numberField("Score", f -> { })
                    .numberField("Bonus", f -> { })
                    .group("Claims", claims -> claims.repeatable(5)
                            .stringField("ClaimNo", f -> { }).numberField("Amount", f -> { }))
                    .group("Payouts", payouts -> payouts.repeatable(5).indexField("PayoutNo")
                            .stringField("PayoutNo", f -> { }).numberField("Total", f -> { })))
            .build().toJson();

    /** Names the error field, so an admitted control is admitted rather than refused for not reading it. */
    private static final String READS_IT = "FieldFilled(Reference) And ";

    private static final String UNEXPECTED = "MVK_UNEXPECTED_TOKEN";

    @Test
    void anOperatorsArgumentIsNeverACondition() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("an operation argument — the control", verdict(READS_IT + "Max([Score], 2) > 0"));
        actual.put("a connective in a field operand", verdict("FieldFilled(Reference And FieldFilled(Reference))"));
        actual.put("a comparison in a field operand", verdict(READS_IT + "FieldFilled(Score > 3)"));
        actual.put("a predicate call as a field operand", verdict(READS_IT + "FieldFilled(FieldFilled(Score))"));
        actual.put("a comparison in an operation argument", verdict(READS_IT + "Max([Score] > 1, 2) > 0"));
        actual.put("a predicate call in an operation argument", verdict(READS_IT + "Abs(FieldFilled(Score)) > 0"));
        actual.put("a parenthesised field operand", verdict(READS_IT + "Sum((Claims*/Amount)) > 0"));
        actual.put("a parenthesised condition as an argument", verdict(READS_IT + "Max(([Score] > 1), 2) > 0"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("an operation argument — the control", KernelLaws.ADMITTED);
        expected.put("a connective in a field operand", UNEXPECTED);
        expected.put("a comparison in a field operand", UNEXPECTED);
        expected.put("a predicate call as a field operand", UNEXPECTED);
        expected.put("a comparison in an operation argument", UNEXPECTED);
        expected.put("a predicate call in an operation argument", UNEXPECTED);
        expected.put("a parenthesised field operand", UNEXPECTED);
        expected.put("a parenthesised condition as an argument", UNEXPECTED);
        assertEquals(expected, actual, "what an operator's argument may be");
    }

    @Test
    void anOperationGroupsWithBracesAndHoldsNoCondition() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("a braced operation — the control", verdict(READS_IT + "[Score] > {[Score] + 1}"));
        actual.put("a comparison in braces", verdict(READS_IT + "[Score] > {[Score] > 1}"));
        actual.put("a parenthesised operation as a side", verdict(READS_IT + "[Score] > ([Score] + 1)"));
        actual.put("a parenthesised condition as a side", verdict(READS_IT + "[Score] > ([Score] > 1)"));
        actual.put("a predicate call as a side", verdict(READS_IT + "[Score] > FieldFilled(Score)"));
        actual.put("a parenthesised condition — the control", verdict(READS_IT + "([Score] > 1 Or [Score] < 0)"));
        actual.put("a parenthesised operation leading a comparison", verdict(READS_IT + "([Score] + 1) > 3"));
        actual.put("a computation, a braced operation — the control", operation("{[Score] + 1}"));
        actual.put("a computation, a parenthesised operation", operation("([Score] + 1)"));
        actual.put("a computation, a predicate call", operation("FieldFilled(Score)"));
        actual.put("a computation, a comparison", operation("[Score] > 1"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("a braced operation — the control", KernelLaws.ADMITTED);
        expected.put("a comparison in braces", UNEXPECTED);
        expected.put("a parenthesised operation as a side", UNEXPECTED);
        expected.put("a parenthesised condition as a side", UNEXPECTED);
        expected.put("a predicate call as a side", UNEXPECTED);
        expected.put("a parenthesised condition — the control", KernelLaws.ADMITTED);
        expected.put("a parenthesised operation leading a comparison", UNEXPECTED);
        expected.put("a computation, a braced operation — the control", KernelLaws.ADMITTED);
        expected.put("a computation, a parenthesised operation", UNEXPECTED);
        expected.put("a computation, a predicate call", UNEXPECTED);
        expected.put("a computation, a comparison", UNEXPECTED);
        assertEquals(expected, actual, "what an operation may hold");
    }

    @Test
    void onlyAFilterClauseTakesACondition() {
        String sum = READS_IT + "Sum(Claims*/Amount Having %s) > 0";
        String keyed = READS_IT + "[/Policy/Payouts/Total For %s] > 0";
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("a filter body — the control", verdict(sum.formatted("[Claims/Amount] > 0")));
        actual.put("a filter body in parentheses", verdict(sum.formatted("([Claims/Amount] > 0)")));
        actual.put("a filter body that is a predicate call", verdict(sum.formatted("FieldFilled(Claims/Amount)")));
        actual.put("a filter body that is an operand alone", verdict(sum.formatted("[Claims/Amount]")));
        actual.put("a key — the control", verdict(keyed.formatted("\"P1\"")));
        actual.put("a comparison in a key", verdict(keyed.formatted("\"P1\" > 3")));
        actual.put("a scope — the control",
                KernelLaws.verdictModel(POLICY, "/Policy", "../Claims/ClaimNo",
                        "RepetitionNotUnique(Claims/ClaimNo @From Claims)"));
        actual.put("a comparison in a scope",
                KernelLaws.verdictModel(POLICY, "/Policy", "../Claims/ClaimNo",
                        "RepetitionNotUnique(Claims/ClaimNo @From Claims > 3)"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("a filter body — the control", KernelLaws.ADMITTED);
        expected.put("a filter body in parentheses", KernelLaws.ADMITTED);
        expected.put("a filter body that is a predicate call", KernelLaws.ADMITTED);
        expected.put("a filter body that is an operand alone", UNEXPECTED);
        expected.put("a key — the control", KernelLaws.ADMITTED);
        expected.put("a comparison in a key", UNEXPECTED);
        expected.put("a scope — the control", KernelLaws.ADMITTED);
        expected.put("a comparison in a scope", UNEXPECTED);
        assertEquals(expected, actual, "which clause takes a condition");
    }

    /**
     * A filter body is a condition like any other, so A12 judges its comparisons as it judges one outside a
     * filter; {@link FilterBodyComparisonLawsTest} measures that for every comparison family. The last row is
     * the control that a comparison AFTER a filter body is judged again.
     */
    @Test
    void aFilterBodysComparisonIsJudgedThereToo() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("a text ordered against a number — the control", verdict(READS_IT + "[Reference] > 5"));
        actual.put("the same inside a filter body",
                verdict(READS_IT + "Sum(Claims*/Amount Having [Claims/ClaimNo] > 5) > 0"));
        actual.put("the same over a field outside the filtered group",
                verdict(READS_IT + "Sum(Claims*/Amount Having [Reference] > 5) > 0"));
        actual.put("its sound twin", verdict(READS_IT + "Sum(Claims*/Amount Having [Reference] == \"x\") > 0"));
        actual.put("the control's comparison after a filter body",
                verdict(READS_IT + "Sum(Claims*/Amount Having [Claims/Amount] > 0) > 0 And [Reference] > 5"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("a text ordered against a number — the control", "MVK_INVALID_TYPE_FOR_COMPARISON");
        expected.put("the same inside a filter body", "MVK_INVALID_TYPE_FOR_COMPARISON");
        expected.put("the same over a field outside the filtered group", "MVK_INVALID_TYPE_FOR_COMPARISON");
        // A body naming nothing the filter iterates has its own class, which the comparison class withholds.
        expected.put("its sound twin", "MVK_NO_ITERATION_FOR_WILDCARD");
        expected.put("the control's comparison after a filter body", "MVK_INVALID_TYPE_FOR_COMPARISON");
        assertEquals(expected, actual, "where a comparison is judged");
    }

    private static String verdict(String condition) {
        return KernelLaws.verdictModel(POLICY, "/Policy", "../Reference", condition);
    }

    private static String operation(String operation) {
        return KernelLaws.computationVerdict(POLICY, DraftComputation.builder()
                .groupPath("/Policy").name("Probe").computedFieldRelPath("../Bonus")
                .alternatives(List.of(new DraftComputation.Alternative(null, operation)))
                .messages(List.of(new LocalizedText("en_US", "see it")))
                .build());
    }
}
