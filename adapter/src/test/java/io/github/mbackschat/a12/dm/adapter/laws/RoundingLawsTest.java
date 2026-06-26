package io.github.mbackschat.a12.dm.adapter.laws;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer;

/**
 * Behavioral law — <b>the rounding operators are directional, and pre-rounded at the internal
 * scale</b> (RT-SEMANTICS-LEDGER: {@code BigDecimalUtils}; KERNEL-FINDINGS §5 supplement).
 *
 * <ul>
 *   <li>{@code RoundDown} is <b>FLOOR</b> (toward −∞) and {@code RoundUp} is <b>CEILING</b>
 *       (toward +∞) — <em>not</em> truncation: {@code RoundDown(−1.4) = −2},
 *       {@code RoundUp(−1.4) = −1}. The negative-value behavior is the modeller trap.</li>
 *   <li>{@code RoundAccounting} is HALF_UP — away from zero on the .5 boundary in both signs.</li>
 *   <li>Before the target-scale rounding, the value is pre-rounded to the internal scale (19,
 *       HALF_UP), so a repeating-decimal artifact can't leak: {@code RoundDown(q/3·3) = q},
 *       not {@code q−1}.</li>
 * </ul>
 */
class RoundingLawsTest {

    private static final String ORDER = RuntimeLaws.model("models/order.dm.json");
    private static final Set<String> ORDER_ROW = Set.of("/Order");

    // Conditions are authored with the rulekit typed DSL and rendered to the canonical string fed to the kernel.
    private static final BoundModel M = BoundModel.ofJson(ORDER);

    private static String render(Condition c) {
        return new DslRenderer().render(c);
    }

    private static String rule(String condition) {
        return RuntimeLaws.withRule(ORDER, "/Order", "RoundProbe", "../Quantity", "ROUND_PROBE", condition);
    }

    @Test
    void roundDownIsFloor_notTruncation() {
        // UnitPrice 0.6 − 2 = −1.4; FLOOR → −2 (truncation would give −1)
        String model = rule(render(
                Dsl.roundDown(M.num("/Order/UnitPrice").asNumber().minus(2L), 0).eq(M.num("/Order/Quantity").asNumber())));

        assertTrue(RuntimeLaws.fires(model, "ROUND_PROBE", ORDER_ROW,
                Map.of("/Order/UnitPrice", "0.6", "/Order/Quantity", "-2")),
                "RoundDown(−1.4) = −2 — floor, toward −∞");
        assertFalse(RuntimeLaws.fires(model, "ROUND_PROBE", ORDER_ROW,
                Map.of("/Order/UnitPrice", "0.6", "/Order/Quantity", "-1")),
                "…not −1 (truncation toward zero)");
    }

    @Test
    void roundUpIsCeiling() {
        // −1.4 CEILING → −1
        String model = rule(render(
                Dsl.roundUp(M.num("/Order/UnitPrice").asNumber().minus(2L), 0).eq(M.num("/Order/Quantity").asNumber())));

        assertTrue(RuntimeLaws.fires(model, "ROUND_PROBE", ORDER_ROW,
                Map.of("/Order/UnitPrice", "0.6", "/Order/Quantity", "-1")),
                "RoundUp(−1.4) = −1 — ceiling, toward +∞");
        assertFalse(RuntimeLaws.fires(model, "ROUND_PROBE", ORDER_ROW,
                Map.of("/Order/UnitPrice", "0.6", "/Order/Quantity", "-2")),
                "…not −2");
    }

    @Test
    void roundAccountingIsHalfUp_awayFromZeroOnTheBoundary() {
        String model = rule(render(
                Dsl.roundAccounting(M.num("/Order/UnitPrice").asNumber().minus(3L), 0).eq(M.num("/Order/Quantity").asNumber())));

        // 0.5 − 3 = −2.5; HALF_UP → −3 (away from zero)
        assertTrue(RuntimeLaws.fires(model, "ROUND_PROBE", ORDER_ROW,
                Map.of("/Order/UnitPrice", "0.5", "/Order/Quantity", "-3")),
                "RoundAccounting(−2.5) = −3 — the .5 boundary rounds away from zero");

        String positive = rule(render(
                Dsl.roundAccounting(M.num("/Order/UnitPrice").asNumber().plus(2L), 0).eq(M.num("/Order/Quantity").asNumber())));
        assertTrue(RuntimeLaws.fires(positive, "ROUND_PROBE", ORDER_ROW,
                Map.of("/Order/UnitPrice", "0.5", "/Order/Quantity", "3")),
                "RoundAccounting(2.5) = 3");
    }

    @Test
    void theInternalPreRoundingAbsorbsRepeatingDecimals() {
        // q/3·3 evaluates to 0.99…9 at the internal precision; the pre-rounding to scale 19
        // HALF_UP restores 1 before the floor — so RoundDown(q/3·3) == q, not q−1
        String model = rule(render(Dsl.roundDown(
                M.num("/Order/Quantity").asNumber().dividedBy(Dsl.number("3")).times(Dsl.number("3")), 0)
                .eq(M.num("/Order/Quantity").asNumber())));

        assertTrue(RuntimeLaws.fires(model, "ROUND_PROBE", ORDER_ROW,
                Map.of("/Order/Quantity", "1")),
                "RoundDown(1/3 · 3) = 1 — the repeating decimal does not floor to 0");
    }
}
