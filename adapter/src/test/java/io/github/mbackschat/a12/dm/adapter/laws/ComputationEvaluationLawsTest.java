package io.github.mbackschat.a12.dm.adapter.laws;

import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.en;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Computation;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberLiteral;
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberRef;

/**
 * Runtime behavioral laws — <b>computation evaluation</b> (RT-SEMANTICS-LEDGER:
 * {@code CalculationController} + {@code VkBigDecimal}):
 *
 * <ul>
 *   <li><b>Division-by-zero public delta</b> — {@code compute()} reports no outcome for a fresh target and
 *       <b>clears</b> a stale target. This test observes only that public delta; IF177 proves that the
 *       underlying numeric domain failure marks the target invalid, carries {@code berechnungsWertFehler},
 *       and poisons a dependent read.</li>
 *   <li><b>Overlapping preconditions: the first declared alternative wins</b> — alternatives are
 *       tried in declaration order; the computed value is scale-normalized ({@code 1.00} → {@code "1"}).</li>
 *   <li>Consistency-tier corollary found en route: a bare <b>division has unknown scale</b> and
 *       cannot assign to a scaled field ({@code MVK_INVALID_COMPARE_DEC_PLACES}, rhs "?") — wrap it
 *       in an explicit rounding ({@code RoundAccounting(expr, n)}) to fix the scale.</li>
 * </ul>
 */
class ComputationEvaluationLawsTest {

    private static final String TARGET = "/Order/UnitPrice";

    private static String divisionModel() {
        BoundModel m = BoundModel.ofJson(RuntimeLaws.model("models/order.dm.json"));
        Computation comp = m.computation(m.field(TARGET)).name("UnitPriceComp")
                // a bare division has UNKNOWN scale and cannot assign to the scale-2 target
                // (MVK_INVALID_COMPARE_DEC_PLACES) — the rounding fixes the scale (see class javadoc)
                .calc(Dsl.roundAccounting(
                        m.num("/Order/Quantity").asNumber().dividedBy(m.num("/Order/BackorderQuantity").asNumber()),
                        2).expression())
                .message(en("Unit price."))
                .build();
        return m.add(comp).toJson();
    }

    private static String overlappingModel() {
        BoundModel m = BoundModel.ofJson(RuntimeLaws.model("models/order.dm.json"));
        NumberRef qty = m.num("/Order/Quantity");
        Computation comp = m.computation(m.field(TARGET)).name("UnitPriceComp")
                .when(qty.ge(0)).calc(NumberLiteral.of("1.00"))
                .when(qty.ge(5)).calc(NumberLiteral.of("2.00"))
                .message(en("Unit price."))
                .build();
        return m.add(comp).toJson();
    }

    @Test
    void aNonZeroDivisorComputesTheQuotient() {
        List<RuntimeLaws.Computed> out = RuntimeLaws.compute(divisionModel(),
                RuntimeLaws.doc().group("/Order", 1)
                        .field("/Order/Quantity", "10", 1, 1)
                        .field("/Order/BackorderQuantity", "4", 1, 1));
        assertTrue(RuntimeLaws.computedValue(out, TARGET).isPresent(),
                () -> "10 / 4 computes a value; got " + out);
    }

    @Test
    void divisionByZeroComputesNothing() {
        // Public-delta observation only: a fresh target produces no Computed record. IF177's
        // DivisionDomainFailureComputationDiffTest owns the target-invalid/formal-error channels.
        List<RuntimeLaws.Computed> out = RuntimeLaws.compute(divisionModel(),
                RuntimeLaws.doc().group("/Order", 1)
                        .field("/Order/Quantity", "10", 1, 1)
                        .field("/Order/BackorderQuantity", "0", 1, 1));
        assertTrue(out.stream().noneMatch(c -> c.path().equals(TARGET)),
                () -> "10 / 0 has no fresh-target public delta; got " + out);
    }

    @Test
    void divisionByZeroOverAStaleValue() {
        // the dangerous corollary: does a previously computed value survive a now-invalid input?
        List<RuntimeLaws.Computed> out = RuntimeLaws.compute(divisionModel(),
                RuntimeLaws.doc().group("/Order", 1)
                        .field("/Order/Quantity", "10", 1, 1)
                        .field("/Order/BackorderQuantity", "0", 1, 1)
                        .field(TARGET, "9.99", 1, 1));
        assertTrue(RuntimeLaws.cleared(out, TARGET),
                () -> "a stale computed value is cleared when the division turns invalid; got " + out);
    }

    @Test
    void theFirstDeclaredAlternativeWinsWhenPreconditionsOverlap() {
        // Quantity = 10 satisfies BOTH preconditions (>= 0 and >= 5); declaration order decides
        List<RuntimeLaws.Computed> out = RuntimeLaws.compute(overlappingModel(),
                RuntimeLaws.doc().group("/Order", 1)
                        .field("/Order/Quantity", "10", 1, 1));
        assertEquals("1", RuntimeLaws.computedValue(out, TARGET).orElse(null),
                () -> "the first declared alternative (1.00, normalized to 1) wins over the later (2.00); got " + out);
    }
}
