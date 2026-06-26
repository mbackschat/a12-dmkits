package io.github.mbackschat.a12.dm.adapter.laws;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer;

/**
 * Behavioral law — <b>polarity</b> (KERNEL-SEMANTICS framing; the #1 eval trap, lint
 * {@code RK_INVERTED_REQUIREDNESS}). A rule's {@code errorCondition} describes the <em>violation</em>:
 * the rule <b>fires when the condition is TRUE</b>, not when the requirement is met. Writing the
 * condition as the requirement silently inverts the rule (kernel-valid, but wrong at runtime) — which
 * only a document-evaluating oracle like this can catch.
 */
class PolarityLawsTest {

    private static final String ORDER = RuntimeLaws.model("models/order.dm.json");
    private static final Set<String> ORDER_ROW = Set.of("/Order");

    // Conditions are authored with the rulekit typed DSL and rendered to the canonical string fed to
    // the kernel — no free-text condition literals.
    private static final BoundModel m = BoundModel.ofJson(ORDER);

    private static String render(Condition c) {
        return new DslRenderer().render(c);
    }

    @Test
    void firesWhenTheErrorConditionHolds_notWhenTheRequirementIsMet() {
        // requirement: "quantity must be <= 100" → the VIOLATION is "[Quantity] > 100"
        String model = RuntimeLaws.withRule(ORDER, "/Order", "QtyCap", "../Quantity", "QTY_TOO_HIGH",
                render(m.num("/Order/Quantity").filled().and(m.num("/Order/Quantity").gt(100))));

        // violating data → fires
        assertTrue(RuntimeLaws.fires(model, "QTY_TOO_HIGH", ORDER_ROW, Map.of("/Order/Quantity", "150")),
                "a rule must FIRE when its error condition (the violation) holds");
        // compliant data → does not fire
        assertFalse(RuntimeLaws.fires(model, "QTY_TOO_HIGH", ORDER_ROW, Map.of("/Order/Quantity", "50")),
                "a rule must NOT fire when the requirement is met");
    }

    @Test
    void invertedPolarityFiresOnTheCompliantCase_theTrapMadeVisible() {
        // the WRONG way (condition = the requirement, not the violation): "[Quantity] <= 100"
        String inverted = RuntimeLaws.withRule(ORDER, "/Order", "QtyCapWrong", "../Quantity", "QTY_WRONG",
                render(m.num("/Order/Quantity").filled().and(m.num("/Order/Quantity").le(100))));

        // the inversion fires on the *compliant* value and stays silent on the *violating* one — backwards
        assertTrue(RuntimeLaws.fires(inverted, "QTY_WRONG", ORDER_ROW, Map.of("/Order/Quantity", "50")),
                "inverted polarity fires on the compliant case (the trap)");
        assertFalse(RuntimeLaws.fires(inverted, "QTY_WRONG", ORDER_ROW, Map.of("/Order/Quantity", "150")),
                "inverted polarity stays silent on the actual violation (the trap)");
    }
}
