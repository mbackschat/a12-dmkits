package io.github.mbackschat.a12.dm.adapter;

import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.en;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Computation;
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.Rule;
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity;

/**
 * F9a whereUsed over <b>computations</b> (the {@link KernelAdapter#whereUsed} peer). A computation is
 * stored as a rule (so it appears in the kernel's {@code getAllRules()}), but it is a different thing —
 * these tests pin the partition: {@link KernelAdapter#whereUsed} stays <em>validation rules only</em>,
 * computations are surfaced by {@link KernelAdapter#whereUsedInComputations}, and a field read only inside
 * a computation's operation (invisible to a rule scan) is found via the computation leg.
 */
class WhereUsedInComputationsTest {

    /** Order with a validation rule on Subtotal and a computation Total = Subtotal + Tax (Tax used only there). */
    private static BoundModel orderWithRuleAndComputation() {
        BoundModel m = BoundModel.ofJson(DocumentModelDraft.named("order").locale("en_US")
                .rootGroup("Order", o -> o
                        .numberField("Subtotal", n -> { })
                        .numberField("Tax", n -> { })
                        .numberField("Total", n -> { }))
                .build().toJson());
        NumberRef subtotal = m.num("/Order/Subtotal");
        Rule cap = m.rule(m.group("/Order")).name("SubtotalCap")
                .errorField(subtotal).when(subtotal.gt(100000))
                .code("SUB_CAP").severity(Severity.ERROR).message(en("Subtotal too large.")).build();
        Computation total = m.computation(m.num("/Order/Total")).name("TotalComp")
                .calc(subtotal.asNumber().plus(m.num("/Order/Tax").asNumber()).expression())
                .message(en("Total is subtotal plus tax.")).build();
        return m.add(cap).add(total);
    }

    @Test
    void ruleFullNamesExcludesComputations() {
        KernelAdapter adapter = KernelAdapter.of(orderWithRuleAndComputation());
        assertEquals(Set.of("/Order/SubtotalCap"), adapter.ruleFullNames(),
                "ruleFullNames is validation rules only");
        assertEquals(Set.of("/Order/TotalComp"), adapter.computationFullNames(),
                "the computation is enumerated separately");
    }

    @Test
    void whereUsedIsValidationRulesOnlyAndComputationsAreAttributedSeparately() {
        KernelAdapter adapter = KernelAdapter.of(orderWithRuleAndComputation());

        // Subtotal is read by both the rule and the computation — each leg attributes its own kind
        assertEquals(Set.of("/Order/SubtotalCap"), adapter.whereUsed("/Order/Subtotal"),
                "the validation rule that checks Subtotal");
        assertEquals(Set.of("/Order/TotalComp"), adapter.whereUsedInComputations("/Order/Subtotal"),
                "the computation that reads Subtotal");
    }

    @Test
    void operandReferencedOnlyInAComputationIsFoundViaTheComputationLeg() {
        KernelAdapter adapter = KernelAdapter.of(orderWithRuleAndComputation());
        // Tax appears only in the computation's operation — no validation rule touches it
        assertTrue(adapter.whereUsed("/Order/Tax").isEmpty(),
                () -> "no validation rule references Tax, got: " + adapter.whereUsed("/Order/Tax"));
        assertEquals(Set.of("/Order/TotalComp"), adapter.whereUsedInComputations("/Order/Tax"),
                "the computation that reads Tax in its operation is surfaced");
    }

    @Test
    void referencedFieldsOfComputationReturnsOperandsAndTheCalculatedField() {
        KernelAdapter adapter = KernelAdapter.of(orderWithRuleAndComputation());
        assertEquals(Set.of("/Order/Subtotal", "/Order/Tax", "/Order/Total"),
                adapter.referencedFieldsOfComputation("/Order/TotalComp"),
                "calculated field plus every operand the computation reads");
    }

    @Test
    void unreferencedEntityHasNoRulesAndNoComputations() {
        KernelAdapter adapter = KernelAdapter.of(orderWithRuleAndComputation());
        assertTrue(adapter.whereUsed("/Order/NoSuchField").isEmpty());
        assertTrue(adapter.whereUsedInComputations("/Order/NoSuchField").isEmpty());
        assertFalse(adapter.whereUsedInComputations("/Order/Total").isEmpty(),
                "the calculated field's own computation is surfaced (impact analysis)");
    }
}
