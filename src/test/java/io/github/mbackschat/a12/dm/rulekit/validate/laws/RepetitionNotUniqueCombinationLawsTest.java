package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * KERNEL-FINDINGS §9 — a condition may carry at most ONE {@code RepetitionNotUnique}, counted as CALLS.
 *
 * <p>The gate is one of A12's whole-RULE checks rather than one of the per-construct ones: it runs after the
 * condition has parsed, over the finished tree, so no arm of a connective hides a second call from it. That
 * also fixes its place in the order — every gate belonging to a construct has already had its say, which is
 * the claim the last row measures rather than assumes.
 *
 * <p><b>The law is about the operator's multiplicity, not the key's.</b> One call taking a COMPOSITE key is
 * admitted, which is what separates "two keys" from "two calls" — and it is the row that makes the rule
 * actionable, because the fix for the rejection is to merge the calls rather than to drop one.
 */
class RepetitionNotUniqueCombinationLawsTest {

    private static final String COMBINATION = "MVK_INVALID_COMBINATION_OF_REPETITON_NOT_UNIQUE";

    private static String verdict(String condition) {
        return KernelLaws.orderVerdict("/Order", "../Items/Sku", condition);
    }

    @Test
    void howManyRepetitionNotUniqueCallsOneConditionMayCarry() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("one call — the control", verdict("RepetitionNotUnique(Items/Sku)"));
        actual.put("one call with a COMPOSITE key", verdict("RepetitionNotUnique(Items/Sku, Items/Count)"));
        actual.put("one call beside an ordinary conjunct",
                verdict("RepetitionNotUnique(Items/Sku) And FieldFilled(Items/Count)"));
        actual.put("two calls over different keys",
                verdict("RepetitionNotUnique(Items/Sku) And RepetitionNotUnique(Items/Count)"));
        actual.put("two calls in opposite arms of an Or",
                verdict("RepetitionNotUnique(Items/Sku) Or RepetitionNotUnique(Items/Count)"));
        actual.put("the same call twice",
                verdict("RepetitionNotUnique(Items/Sku) And RepetitionNotUnique(Items/Sku)"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("one call — the control", KernelLaws.ADMITTED);
        // The discriminator between "two keys" and "two calls": merging is the fix the rejection asks for,
        // so a grid without this row would license a checker that reports on key count.
        expected.put("one call with a COMPOSITE key", KernelLaws.ADMITTED);
        expected.put("one call beside an ordinary conjunct", KernelLaws.ADMITTED);
        expected.put("two calls over different keys", COMBINATION);
        // Both arms are counted, which is what makes this a whole-tree gate rather than a per-branch one:
        // no evaluation of the condition can reach both calls, and A12 refuses it anyway.
        expected.put("two calls in opposite arms of an Or", COMBINATION);
        expected.put("the same call twice", COMBINATION);
        assertEquals(expected, actual, "how many RepetitionNotUnique calls one condition may carry");
    }

    /**
     * A gate belonging to one CONSTRUCT reports ahead of this whole-rule one.
     *
     * <p>Both rows carry two calls, so the combination gate would answer on its own; each also breaks a rule
     * of the construct itself, and the construct's class is what comes back.
     */
    @Test
    void aConstructsOwnGateReportsAheadOfTheWholeRuleOne() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("two calls, one scoped to a group that does not repeat",
                verdict("RepetitionNotUnique(Items/Sku @From ShippingAddress) "
                        + "And RepetitionNotUnique(Items/Count)"));
        actual.put("two calls, one keyed on a group",
                verdict("RepetitionNotUnique(Items) And RepetitionNotUnique(Items/Count)"));
        actual.put("two calls, the second inside a Having filter",
                verdict("RepetitionNotUnique(Items/Sku) And "
                        + "Sum(Items*/Count Having RepetitionNotUnique(Items/Sku)) > 0"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("two calls, one scoped to a group that does not repeat", "MVK_GROUP_NOT_REPEATABLE");
        expected.put("two calls, one keyed on a group", "MVK_NO_GROUPS_ALLOWED");
        // The row the portable checker needed before it could stop counting a filter's call: the construct
        // is refused in that POSITION, so the second call is not a second defect and a checker reporting the
        // combination class there would name a class A12 never draws on this model.
        expected.put("two calls, the second inside a Having filter",
                "MVK_DISALLOWED_CONSTRUCT_IN_FILTER_CONDITION");
        assertEquals(expected, actual, "a construct's own gate reports ahead of the whole-rule one");
    }
}
