package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;

/**
 * KERNEL-FINDINGS KF656 — on kernel 31.1.1 a comparison reading {@code CurrentRepetition} refuses at every level
 * the rule iterates, whichever level the operation reads: kernel 30.8.1 refused it only where it iterated the
 * judged level, so an outer group's repetition read beside an inner per-row field was admitted.
 *
 * <p>The {@code And} rows are the control that the refusal is the iteration guard's: a positive read of the bound
 * row rescues the comparison, as it rescues every negative part.
 *
 * <p>Own domain (insurance claims and their parts).
 */
class CurrentRepetitionIterationGuardLawsTest {

    private static final String POLICY = DocumentModelDraft.named("policy-current-repetition").locale("en_US")
            .rootGroup("Policy", p -> p
                    .group("Claims", c -> c.repeatable(3)
                            .numberField("Amount", n -> n.fractionDigits(0))
                            .group("Parts", d -> d.repeatable(3).numberField("Qty", n -> n.fractionDigits(0)))))
            .build().toJson();

    @Test
    void aRepetitionReadRefusesAtEveryLevelTheRuleIterates() {
        Map<String, String[]> rows = new LinkedHashMap<>();
        rows.put("the outer repetition beside an inner read",
                new String[] {"Claims/Parts/Qty", "[Claims/Parts/Qty] > CurrentRepetition(Claims)"});
        rows.put("the same, operands swapped",
                new String[] {"Claims/Parts/Qty", "CurrentRepetition(Claims) > [Claims/Parts/Qty]"});
        rows.put("the inner repetition beside an inner read",
                new String[] {"Claims/Parts/Qty", "[Claims/Parts/Qty] > CurrentRepetition(Claims/Parts)"});
        rows.put("the read's own repetition",
                new String[] {"Claims/Amount", "[Claims/Amount] > CurrentRepetition(Claims)"});
        rows.put("the outer repetition, rescued by a read of the bound row", new String[] {"Claims/Parts/Qty",
                "FieldFilled(Claims/Parts/Qty) And [Claims/Parts/Qty] > CurrentRepetition(Claims)"});

        Map<String, String> expected = new LinkedHashMap<>();
        rows.keySet().forEach(row -> expected.put(row, "MVK_NEG_CONDITION_IN_ITERATION"));
        expected.put("the outer repetition, rescued by a read of the bound row", KernelLaws.ADMITTED);

        Map<String, String> actual = new LinkedHashMap<>();
        rows.forEach((row, r) -> actual.put(row, KernelLaws.verdictModel(POLICY, "/Policy", "../" + r[0], r[1])));
        assertEquals(expected, actual);
    }
}
