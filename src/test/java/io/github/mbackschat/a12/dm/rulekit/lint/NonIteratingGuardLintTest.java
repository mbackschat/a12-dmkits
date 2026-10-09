package io.github.mbackschat.a12.dm.rulekit.lint;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldRef;

/**
 * {@code RK_NEG_CONDITION_IN_ITERATION} does not count a positive that iterates at no level as a guard. The rows
 * and the kernel's refusals are {@code validate.laws.IterationGuardNonIteratingPositiveLawsTest}'s; before
 * 2026-10-09 each of them was silent here, because every such presence or comparison licensed any level.
 */
class NonIteratingGuardLintTest {

    private static final BoundModel M = BoundModel.ofJson(DocumentModelDraft.named("lint-non-iterating-guard")
            .locale("en_US")
            .rootGroup("Order", o -> o
                    .field("Reference", FieldKind.STRING)
                    .group("Customer", c -> c.field("Name", FieldKind.STRING))
                    .group("Items", i -> i.repeatable(20)
                            .field("Sku", FieldKind.STRING)
                            .field("Count", FieldKind.NUMBER)))
            .build().toJson());
    private static final FieldRef SKU = M.field("/Order/Items/Sku");

    @Test
    void aPositiveAtNoRepeatableLevelLeavesTheNegativeUnguarded() {
        Map<String, Boolean> actual = new LinkedHashMap<>();
        actual.put("same-level guard", warns(Dsl.fieldFilled(M.field("/Order/Items/Count"))));
        actual.put("root field presence", warns(Dsl.fieldFilled(M.field("/Order/Reference"))));
        actual.put("root field comparison", warns(M.str("/Order/Reference").eq("A-1")));
        actual.put("non-repeating group presence", warns(Dsl.groupFilled(M.group("/Order/Customer"))));
        actual.put("field of a non-repeating group", warns(Dsl.fieldFilled(M.field("/Order/Customer/Name"))));
        actual.put("root group presence", warns(Dsl.groupFilled(M.group("/Order"))));

        Map<String, Boolean> expected = new LinkedHashMap<>();
        expected.put("same-level guard", false);
        expected.put("root field presence", true);
        expected.put("root field comparison", true);
        expected.put("non-repeating group presence", true);
        expected.put("field of a non-repeating group", true);
        expected.put("root group presence", true);
        assertEquals(expected, actual, "which positives the lint counts as a single-level guard");
    }

    private static boolean warns(Condition positive) {
        return new Linter().lint(M, SKU.path().value(), positive.and(Dsl.fieldNotFilled(SKU))).stream()
                .anyMatch(d -> d.code().map("RK_NEG_CONDITION_IN_ITERATION"::equals).orElse(false));
    }
}
