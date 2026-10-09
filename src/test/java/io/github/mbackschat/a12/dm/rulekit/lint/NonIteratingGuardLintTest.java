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
import io.github.mbackschat.a12.dm.rulekit.dsl.Rule;
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity;

/**
 * {@code RK_NEG_CONDITION_IN_ITERATION} does not count a positive that iterates at no level as a guard. The rows
 * and the kernel's refusals are {@code validate.laws.IterationGuardNonIteratingPositiveLawsTest}'s; before
 * 2026-10-09 each of them was silent here, because every such presence or comparison licensed any level. The
 * {@code Or} rows are {@code validate.laws.IterationGuardNestedCompositionLawsTest}'s.
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
        actual.put("a field list at no repeatable level", warns(Dsl.atLeastOneFieldFilled(
                M.field("/Order/Reference"), M.field("/Order/Customer/Name"))));
        actual.put("an Or whose every arm is a same-level guard", warns(Dsl.fieldFilled(M.field("/Order/Items/Count"))
                .or(Dsl.fieldFilled(SKU))));
        actual.put("an Or of a same-level presence and comparison", warns(Dsl.fieldFilled(M.field("/Order/Items/Count"))
                .or(M.num("/Order/Items/Count").gt(0))));
        actual.put("the mirror: a comparison Or a presence", warns(M.num("/Order/Items/Count").gt(0)
                .or(Dsl.fieldFilled(M.field("/Order/Items/Count")))));
        actual.put("an Or with one same-level arm", warns(Dsl.fieldFilled(M.field("/Order/Items/Count"))
                .or(Dsl.fieldFilled(M.field("/Order/Reference")))));
        actual.put("the rule group's presence, the rule in the non-repeating root",
                warnsInGroup("/Order", Dsl.ruleGroup().filled()));
        actual.put("the rule group's presence, the rule in the repeatable group",
                warnsInGroup("/Order/Items", Dsl.ruleGroup().filled()));

        Map<String, Boolean> expected = new LinkedHashMap<>();
        expected.put("same-level guard", false);
        expected.put("root field presence", true);
        expected.put("root field comparison", true);
        expected.put("non-repeating group presence", true);
        expected.put("field of a non-repeating group", true);
        expected.put("root group presence", true);
        expected.put("a field list at no repeatable level", true);
        expected.put("an Or whose every arm is a same-level guard", false);
        expected.put("an Or of a same-level presence and comparison", false);
        expected.put("the mirror: a comparison Or a presence", false);
        expected.put("an Or with one same-level arm", true);
        expected.put("the rule group's presence, the rule in the non-repeating root", true);
        expected.put("the rule group's presence, the rule in the repeatable group", false);
        assertEquals(expected, actual, "which positives the lint counts as a single-level guard");
    }

    private static boolean warnsInGroup(String groupPath, Condition positive) {
        Rule rule = M.rule(M.group(groupPath)).name("GuardProbe").errorField(SKU)
                .when(positive.and(Dsl.fieldNotFilled(SKU))).code("GUARD").severity(Severity.ERROR)
                .message(Dsl.en("Guard probe.")).build();
        return new Linter().lint(M, rule).stream()
                .anyMatch(d -> d.code().map("RK_NEG_CONDITION_IN_ITERATION"::equals).orElse(false));
    }

    private static boolean warns(Condition positive) {
        return new Linter().lint(M, SKU.path().value(), positive.and(Dsl.fieldNotFilled(SKU))).stream()
                .anyMatch(d -> d.code().map("RK_NEG_CONDITION_IN_ITERATION"::equals).orElse(false));
    }
}
