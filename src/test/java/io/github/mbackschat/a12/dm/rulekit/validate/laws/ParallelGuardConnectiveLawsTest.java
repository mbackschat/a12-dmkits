package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.ModelDrafts;

/**
 * KERNEL-FINDINGS §9 — a parallel iteration's negative is guarded through the authored connective tree, not
 * by a positive leaf anywhere (a12-kernel-lean SPEC-2026-10-09-09).
 *
 * <p>An {@code And} is admitted when one child references and guards the jointly iterated level; an {@code Or}
 * only when every child does. Each row is chosen so that a rival account predicts the other verdict: "any
 * positive leaf guards" admits the {@code Or} and the off-level rows, "every {@code Or} must hold wherever it
 * sits" refuses the {@code Or} nested under a guarded {@code And}, and "the two joined groups are separate
 * levels" refuses a guard on the other group.
 *
 * <p>Uses the logistics draft, whose {@code Lines} and {@code Picks} share the index field {@code LineRef}, with
 * the rule in {@code Lines}; every condition names the error field.
 */
class ParallelGuardConnectiveLawsTest {

    private static final String LOGISTICS = ModelDrafts.logistics().toJson();
    private static final String INVALID = "MVK_PARALLEL_ITERATION_INVALID";

    private static final String LINE_FILLED = "FieldFilled(/Shipment/Lines/OrderedQty)";
    private static final String LINE_EMPTY = "FieldNotFilled(/Shipment/Lines/OrderedQty)";
    private static final String PICK_FILLED = "FieldFilled(/Shipment/Picks/PickedQty)";
    private static final String PICK_EMPTY = "FieldNotFilled(/Shipment/Picks/PickedQty)";

    @Test
    void theGuardFollowsTheConnectiveTree() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("a negative And a guard on the other group", verdict(PICK_FILLED + " And " + LINE_EMPTY));
        actual.put("the mirror: a guard on the error field's group", verdict(LINE_FILLED + " And " + PICK_EMPTY));
        actual.put("a negative Or a guard", verdict(PICK_FILLED + " Or " + LINE_EMPTY));
        actual.put("two guards under Or — the Or control", verdict(LINE_FILLED + " Or " + PICK_FILLED));
        actual.put("two guarded conjunctions under Or",
                verdict("(" + PICK_FILLED + " And " + LINE_EMPTY + ") Or (" + LINE_FILLED + " And " + PICK_EMPTY + ")"));
        actual.put("a guarded conjunction Or a bare negative",
                verdict("(" + PICK_FILLED + " And " + LINE_EMPTY + ") Or " + PICK_EMPTY));
        actual.put("an unguarded Or inside a guarded And",
                verdict("(" + PICK_FILLED + " Or " + LINE_EMPTY + ") And " + LINE_FILLED));
        actual.put("a positive that does not iterate",
                verdict("FieldFilled(/Shipment/ShipmentRef) And " + LINE_EMPTY + " And " + PICK_EMPTY));
        actual.put("two negatives — the refusal control", verdict(LINE_EMPTY + " And " + PICK_EMPTY));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("a negative And a guard on the other group", KernelLaws.ADMITTED);
        expected.put("the mirror: a guard on the error field's group", KernelLaws.ADMITTED);
        expected.put("a negative Or a guard", INVALID);
        expected.put("two guards under Or — the Or control", KernelLaws.ADMITTED);
        expected.put("two guarded conjunctions under Or", KernelLaws.ADMITTED);
        expected.put("a guarded conjunction Or a bare negative", INVALID);
        expected.put("an unguarded Or inside a guarded And", KernelLaws.ADMITTED);
        expected.put("a positive that does not iterate", INVALID);
        expected.put("two negatives — the refusal control", INVALID);
        assertEquals(expected, actual, "how a parallel iteration's connective tree guards its negatives");
    }

    private static String verdict(String condition) {
        return KernelLaws.verdictModel(LOGISTICS, "/Shipment/Lines", "../OrderedQty", condition);
    }
}
