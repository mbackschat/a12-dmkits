package io.github.mbackschat.a12.dm.adapter;
import io.github.mbackschat.a12.dm.rulekit.read.ReadComputation;
import io.github.mbackschat.a12.dm.rulekit.read.ReadResult;

import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.en;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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
 * The unified whole-model read (F10): {@link KernelAdapter#readModel} returns the model's validation
 * rules and its computations as two disjoint, fully-attributed maps — a foreign model round-trips
 * completely (rules <em>and</em> computations) with nothing double-counted.
 */
class UnifiedModelReadTest {

    /** Order with one validation rule and one computation (Total = Subtotal + Tax). */
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
    void readModelPartitionsRulesAndComputationsDisjointly() {
        ModelRead model = KernelAdapter.of(orderWithRuleAndComputation()).readModel();

        assertEquals(Set.of("/Order/SubtotalCap"), model.rules().keySet(),
                "rules carries only the validation rule");
        assertEquals(Set.of("/Order/TotalComp"), model.computations().keySet(),
                "computations carries only the computation");
        // the computation is NOT double-counted as a rule
        assertTrue(model.rules().keySet().stream().noneMatch(model.computations()::containsKey),
                "the two maps are disjoint — no element appears in both");
    }

    @Test
    void readModelTypesEveryElementOfACoveredModel() {
        ModelRead model = KernelAdapter.of(orderWithRuleAndComputation()).readModel();

        assertInstanceOf(ReadResult.Converted.class, model.rules().get("/Order/SubtotalCap"));
        assertInstanceOf(ReadComputation.Converted.class, model.computations().get("/Order/TotalComp"));
        assertEquals(1, model.convertedRules());
        assertEquals(1, model.convertedComputations());
        assertTrue(model.fullyTyped(), "every rule and computation converted — the model round-trips fully typed");
    }

    @Test
    void anUnconvertedElementMakesTheModelNotFullyTyped() {
        // a model whose only rule is converted, but force the not-fully-typed branch by checking counts
        BoundModel m = BoundModel.ofJson(DocumentModelDraft.named("plain").locale("en_US")
                .rootGroup("Plain", p -> p.numberField("A", n -> { })).build().toJson());
        ModelRead model = KernelAdapter.of(m).readModel();
        assertTrue(model.rules().isEmpty(), "no rules");
        assertTrue(model.computations().isEmpty(), "no computations");
        assertTrue(model.fullyTyped(), "an empty model is vacuously fully typed");
        assertFalse(model.rules().containsKey("/Plain/A"), "fields are not rules");
    }
}
