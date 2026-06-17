package io.github.mbackschat.a12.dm.adapter.laws;

import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.en;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Computation;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;

/**
 * KERNEL-FINDINGS — "every computation is also a validation rule" (BA "Computation Rules as Validation
 * Rules"): the kernel auto-generates an implicit {@code FieldFilled(F) And [F] != <term>} validation for
 * each computation, so {@code validateFull} fires when a stored computed value disagrees with its term.
 */
class ComputationValidationLawsTest {

    private static final String COVERAGE = "/Order/CoverageWindow";

    private static String modelWithComputation() {
        BoundModel m = BoundModel.ofJson(RuntimeLaws.model("models/order.dm.json"));
        Computation comp = m.computation(m.field(COVERAGE)).name("CoverageWindowComp")
                .calc(Dsl.dateRange(m.date("/Order/OrderDate"), m.date("/Order/DeliveryDate")))
                .message(en("Coverage window.")).build();
        return m.add(comp).toJson();
    }

    private static RuntimeLaws.Doc doc(String coverage) {
        return RuntimeLaws.doc().group("/Order", 1)
                .field("/Order/OrderDate", "2024-01-01", 1, 1)
                .field("/Order/DeliveryDate", "2024-02-01", 1, 1)
                .field(COVERAGE, coverage, 1, 1); // term computes 01.01.2024-01.02.2024
    }

    @Test
    void aStoredValueDisagreeingWithTheComputedTermFires() {
        // The implicit validation fires under its computation's NAME as the errorCode.
        assertTrue(RuntimeLaws.fires(modelWithComputation(), "CoverageWindowComp", doc("01.01.2020-01.01.2021")),
                "a stored value disagreeing with the computed term fires the implicit computation-validation");
    }

    @Test
    void aStoredValueAgreeingWithTheComputedTermDoesNotFire() {
        assertFalse(RuntimeLaws.fires(modelWithComputation(), "CoverageWindowComp", doc("01.01.2024-01.02.2024")),
                "an agreeing stored value does not fire");
    }
}
