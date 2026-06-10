package io.github.mbackschat.a12.dm.adapter.laws;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * Behavioral law — <b>the tolerance comparisons fire strictly beyond the tolerance</b>
 * (RT-SEMANTICS-LEDGER: {@code BedingungsOperatorHelper.vergleiche}, the {@code NE1/NE2/NE5/NE10}
 * branches; KERNEL-FINDINGS §5 supplement). {@code [a] DiffersWithToleranceRange{N} [b]} is satisfied
 * iff {@code |a − b| > N} — a difference of <em>exactly</em> N is still within tolerance and does
 * <b>not</b> fire. The boundary is the trap a modeller would guess either way.
 */
class ToleranceComparisonLawsTest {

    private static final String ORDER = RuntimeLaws.model("models/order.dm.json");
    private static final Set<String> ORDER_ROW = Set.of("/Order");

    @Test
    void aDifferenceOfExactlyTheToleranceDoesNotFire() {
        String model = RuntimeLaws.withRule(ORDER, "/Order", "QtyDrift", "../Quantity", "QTY_DRIFT",
                "[Quantity] DiffersWithToleranceRange2 [BackorderQuantity]");

        assertFalse(RuntimeLaws.fires(model, "QTY_DRIFT", ORDER_ROW,
                Map.of("/Order/Quantity", "10", "/Order/BackorderQuantity", "8")),
                "|10 − 8| = 2 is within (not beyond) tolerance 2 → does not fire");
    }

    @Test
    void aDifferenceJustBeyondTheToleranceFires() {
        String model = RuntimeLaws.withRule(ORDER, "/Order", "QtyDrift", "../Quantity", "QTY_DRIFT",
                "[Quantity] DiffersWithToleranceRange2 [BackorderQuantity]");

        assertTrue(RuntimeLaws.fires(model, "QTY_DRIFT", ORDER_ROW,
                Map.of("/Order/Quantity", "11", "/Order/BackorderQuantity", "8")),
                "|11 − 8| = 3 > 2 → fires");
    }

    @Test
    void theToleranceIsSymmetric() {
        String model = RuntimeLaws.withRule(ORDER, "/Order", "QtyDrift", "../Quantity", "QTY_DRIFT",
                "[Quantity] DiffersWithToleranceRange2 [BackorderQuantity]");

        assertFalse(RuntimeLaws.fires(model, "QTY_DRIFT", ORDER_ROW,
                Map.of("/Order/Quantity", "8", "/Order/BackorderQuantity", "10")),
                "|8 − 10| = 2 — the absolute difference is what counts, direction does not");
        assertTrue(RuntimeLaws.fires(model, "QTY_DRIFT", ORDER_ROW,
                Map.of("/Order/Quantity", "8", "/Order/BackorderQuantity", "11")),
                "|8 − 11| = 3 > 2 → fires in the other direction too");
    }
}
