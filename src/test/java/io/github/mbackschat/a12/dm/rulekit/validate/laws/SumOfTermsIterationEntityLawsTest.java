package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;

/**
 * KERNEL-FINDINGS KF656 — what kernel 31.1.1 asks of {@code SumOfTerms}' iteration entity: a repeatable group,
 * starred at its lowest level ({@code MVK_WILDCARD_AT_LOWEST_LEVEL_REQUIRED}, the 30.8.1 name of the product sum's
 * class), with a term that iterates that level ({@code MVK_NO_ITERATION_FOR_LOWEST_WILDCARD}). It is the one slot
 * that takes a filtered group.
 *
 * <p>The unstarred rows are taken on both carriers, because the iteration walk answers an unstarred group first
 * where the rule does not iterate it.
 *
 * <p>Own domain (insurance claims).
 */
class SumOfTermsIterationEntityLawsTest {

    private static final String POLICY = DocumentModelDraft.named("policy-sum-of-terms").locale("en_US")
            .rootGroup("Policy", p -> p
                    .stringField("Holder", f -> { })
                    .group("Claims", c -> c.repeatable(3)
                            .numberField("Amount", n -> n.fractionDigits(0))
                            .numberField("Reserve", n -> n.fractionDigits(0))))
            .build().toJson();

    @Test
    void theIterationEntityIsAStarredRepeatableGroupWithAnIteratingTerm() {
        Map<String, String[]> rows = new LinkedHashMap<>();
        rows.put("a starred group and an iterating term — the control",
                new String[] {"Holder", "FieldFilled(Holder) And SumOfTerms(Claims*, [Claims/Amount]) > 0"});
        rows.put("a term that is an operation",
                new String[] {"Holder", "FieldFilled(Holder) And SumOfTerms(Claims*, [Claims/Amount] + [Claims/Reserve]) > 0"});
        rows.put("a filtered group", new String[] {"Holder",
                "FieldFilled(Holder) And SumOfTerms(Claims* Having [Claims/Amount] > 5, [Claims/Amount]) > 0"});
        rows.put("an unstarred group the rule iterates",
                new String[] {"Claims/Amount", "SumOfTerms(Claims, [Claims/Amount]) > 0"});
        rows.put("an unstarred group the rule does not iterate",
                new String[] {"Holder", "SumOfTerms(Claims, [Claims/Amount]) > 0"});
        rows.put("a term that does not iterate", new String[] {"Holder", "SumOfTerms(Claims*, 5) > 0"});
        rows.put("a field in the slot", new String[] {"Holder", "SumOfTerms(Claims*/Amount, [Claims/Amount]) > 0"});

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("a starred group and an iterating term — the control", KernelLaws.ADMITTED);
        expected.put("a term that is an operation", KernelLaws.ADMITTED);
        expected.put("a filtered group", KernelLaws.ADMITTED);
        expected.put("an unstarred group the rule iterates", "MVK_WILDCARD_AT_LOWEST_LEVEL_REQUIRED");
        expected.put("an unstarred group the rule does not iterate", "MVK_NO_WILDCARD");
        expected.put("a term that does not iterate", "MVK_NO_ITERATION_FOR_LOWEST_WILDCARD");
        expected.put("a field in the slot", "MVK_NO_FIELDS_ALLOWED");

        Map<String, String> actual = new LinkedHashMap<>();
        rows.forEach((row, r) -> actual.put(row, KernelLaws.verdictModel(POLICY, "/Policy", "../" + r[0], r[1])));
        assertEquals(expected, actual);
    }
}
