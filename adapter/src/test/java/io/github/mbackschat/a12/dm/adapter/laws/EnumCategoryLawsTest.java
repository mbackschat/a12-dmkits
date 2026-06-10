package io.github.mbackschat.a12.dm.adapter.laws;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * Behavioral law — <b>enum-category access maps the stored value positionally and compares the
 * category value</b> (RT-SEMANTICS-LEDGER: {@code RuntimeController} dispatch tail; the model-side
 * positional mapping is KERNEL-FINDINGS §8). Order's {@code Priority} declares values
 * {@code STANDARD, EXPRESS, OVERNIGHT} with category {@code SpeedClass = NORMAL, FAST, FAST} —
 * a many-to-one mapping, so the category comparison is the idiomatic "treat these values alike".
 */
class EnumCategoryLawsTest {

    private static final String ORDER = RuntimeLaws.model("models/order.dm.json");
    private static final Set<String> ORDER_ROW = Set.of("/Order");

    private static String model() {
        return RuntimeLaws.withRule(ORDER, "/Order", "FastLane", "../Priority", "FAST_LANE",
                "[Priority -> SpeedClass] == \"FAST\"");
    }

    @Test
    void bothFastMappedValuesFire_theNormalOneDoesNot() {
        assertTrue(RuntimeLaws.fires(model(), "FAST_LANE", ORDER_ROW,
                Map.of("/Order/CustomerName", "Acme", "/Order/Priority", "EXPRESS")),
                "EXPRESS maps to FAST → fires");
        assertTrue(RuntimeLaws.fires(model(), "FAST_LANE", ORDER_ROW,
                Map.of("/Order/CustomerName", "Acme", "/Order/Priority", "OVERNIGHT")),
                "OVERNIGHT maps to FAST too — the many-to-one mapping is the point");
        assertFalse(RuntimeLaws.fires(model(), "FAST_LANE", ORDER_ROW,
                Map.of("/Order/CustomerName", "Acme", "/Order/Priority", "STANDARD")),
                "STANDARD maps to NORMAL → does not fire");
    }

    @Test
    void anEmptyEnumIsNotEvaluatedThroughItsCategoryEither() {
        assertFalse(RuntimeLaws.fires(model(), "FAST_LANE", ORDER_ROW, Set.of("/Order/Priority"),
                Map.of("/Order/CustomerName", "Acme")),
                "an empty enum rides the not-evaluated track — the category hop doesn't change that");
    }
}
