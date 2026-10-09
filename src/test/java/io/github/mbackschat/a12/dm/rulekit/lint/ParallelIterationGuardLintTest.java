package io.github.mbackschat.a12.dm.rulekit.lint;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.ModelDrafts;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldRef;

/**
 * {@code RK_NEG_CONDITION_IN_ITERATION} warns on a parallel iteration exactly where the kernel refuses it with
 * {@code MVK_PARALLEL_ITERATION_INVALID}: the rows and verdicts are {@code validate.laws.ParallelGuardConnectiveLawsTest}'s,
 * where a guard on either joined group admits the negative and the connective tree decides the rest.
 */
class ParallelIterationGuardLintTest {

    private static final BoundModel M = BoundModel.ofJson(ModelDrafts.logistics().toJson());
    private static final FieldRef ORDERED = M.field("/Shipment/Lines/OrderedQty");
    private static final FieldRef PICKED = M.field("/Shipment/Picks/PickedQty");

    @Test
    void itWarnsWhereTheKernelRefusesTheParallelGuard() {
        Condition lineFilled = Dsl.fieldFilled(ORDERED);
        Condition lineEmpty = Dsl.fieldNotFilled(ORDERED);
        Condition pickFilled = Dsl.fieldFilled(PICKED);
        Condition pickEmpty = Dsl.fieldNotFilled(PICKED);
        Map<String, Boolean> actual = new LinkedHashMap<>();
        actual.put("a negative And a guard on the other group", warns(pickFilled.and(lineEmpty)));
        actual.put("the mirror: a guard on the error field's group", warns(lineFilled.and(pickEmpty)));
        actual.put("a negative Or a guard", warns(pickFilled.or(lineEmpty)));
        actual.put("two guards under Or", warns(lineFilled.or(pickFilled)));
        actual.put("an Or of two guards conjoined with a negative", warns(lineFilled.or(pickFilled).and(lineEmpty)));
        actual.put("two guarded conjunctions under Or",
                warns(pickFilled.and(lineEmpty).or(lineFilled.and(pickEmpty))));
        actual.put("a guarded conjunction Or a bare negative", warns(pickFilled.and(lineEmpty).or(pickEmpty)));
        actual.put("an unguarded Or inside a guarded And", warns(pickFilled.or(lineEmpty).and(lineFilled)));
        actual.put("a positive that does not iterate",
                warns(Dsl.fieldFilled(M.field("/Shipment/ShipmentRef")).and(lineEmpty).and(pickEmpty)));
        actual.put("two negatives", warns(lineEmpty.and(pickEmpty)));

        Map<String, Boolean> expected = new LinkedHashMap<>();
        expected.put("a negative And a guard on the other group", false);
        expected.put("the mirror: a guard on the error field's group", false);
        expected.put("a negative Or a guard", true);
        expected.put("two guards under Or", false);
        expected.put("an Or of two guards conjoined with a negative", false);
        expected.put("two guarded conjunctions under Or", false);
        expected.put("a guarded conjunction Or a bare negative", true);
        expected.put("an unguarded Or inside a guarded And", false);
        expected.put("a positive that does not iterate", true);
        expected.put("two negatives", true);
        assertEquals(expected, actual, "the linter's warnings against the kernel's parallel-guard verdicts");
    }

    private static boolean warns(Condition condition) {
        return new Linter().lint(M, ORDERED.path().value(), condition).stream()
                .anyMatch(d -> d.code().map("RK_NEG_CONDITION_IN_ITERATION"::equals).orElse(false));
    }
}
