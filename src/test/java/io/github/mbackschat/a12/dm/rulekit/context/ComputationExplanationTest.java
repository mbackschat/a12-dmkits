package io.github.mbackschat.a12.dm.rulekit.context;

import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.de;
import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.en;
import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.fieldFilled;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.Fixtures;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Computation;
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberLiteral;
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.RoundingMode;

/**
 * F9 explain for <b>computations</b> ({@link ModelContext#explainComputation}, the calculated-field peer
 * of {@code explain(Condition)}): a computation projects to its computed field (+kind), the common
 * precondition and each alternative's precondition/operation (operators with catalog meaning + referenced
 * fields), its messages, and a gloss — the same projection whether built or read-foreign.
 */
class ComputationExplanationTest {

    @Test
    void projectsCalculatedFieldPreconditionsOperationsAndMessages() {
        BoundModel m = Fixtures.subscription();
        NumberRef base = m.num("/Subscription/Billing/BaseFee");
        Computation comp = m.computation(m.num("/Subscription/Billing/EffectiveFee")).name("EffectiveFeeComp")
                .commonPrecondition(fieldFilled(base.field()))
                .when(base.ge(100)).calc(base.value())
                .when(base.lt(100)).calc(NumberLiteral.of("0.00"))
                .message(en("EffectiveFee follows the tiers."), de("Effektivgebühr nach Stufen.")).build();

        ComputationExplanation ex = new ModelContext(m).explainComputation(comp);

        assertEquals("/Subscription/Billing/EffectiveFee", ex.computedField());
        assertEquals("NUMBER", ex.computedFieldKind());
        assertTrue(ex.commonPrecondition().isPresent(), "the shared guard is explained like a rule");
        assertTrue(ex.commonPrecondition().get().references().stream()
                        .anyMatch(r -> r.path().equals("/Subscription/Billing/BaseFee")),
                () -> "common precondition references BaseFee: " + ex.commonPrecondition());

        assertEquals(2, ex.alternatives().size(), "two tiers");
        ComputationExplanation.Alternative first = ex.alternatives().get(0);
        assertTrue(first.precondition().isPresent(), "tiered alternatives carry a precondition");
        assertTrue(first.operation().references().stream()
                        .anyMatch(r -> r.path().equals("/Subscription/Billing/BaseFee") && r.kind().equals("NUMBER")),
                () -> "the operation reads BaseFee: " + first.operation().references());

        assertTrue(ex.messages().stream().anyMatch(msg -> msg.text().contains("tiers")),
                () -> "self-validation messages recovered: " + ex.messages());

        String json = ex.toJson();
        assertTrue(json.contains("\"computedField\"") && json.contains("\"alternatives\"")
                        && json.contains("\"operation\"") && json.contains("\"commonPrecondition\""),
                () -> "JSON carries the key sections: " + json);
    }

    @Test
    void glossNamesAnUnconditionalComputation() {
        BoundModel m = Fixtures.subscription();
        Computation comp = m.computation(m.num("/Subscription/Billing/EffectiveFee")).name("EffectiveFeeComp")
                .calc(m.num("/Subscription/Billing/BaseFee").value())
                .message(en("EffectiveFee equals BaseFee."), de("Effektivgebühr = Basisgebühr.")).build();

        ComputationExplanation ex = new ModelContext(m).explainComputation(comp);

        assertEquals(1, ex.alternatives().size());
        assertTrue(ex.alternatives().get(0).precondition().isEmpty(), "the single alternative is unconditional");
        assertTrue(ex.commonPrecondition().isEmpty());
        assertTrue(ex.gloss().contains("unconditionally"), () -> "gloss: " + ex.gloss());
        assertFalse(ex.toJson().contains("\"commonPrecondition\""), "no common precondition in JSON");
    }

    @Test
    void aBuilderLeavesEveryUnsetAspectAtItsDefault() {
        ComputationExplanation ex = ComputationExplanation.builder()
                .computedField("/Subscription/Billing/EffectiveFee").computedFieldKind("NUMBER")
                .gloss("EffectiveFee is computed unconditionally.").build();

        assertTrue(ex.commonPrecondition().isEmpty());
        assertTrue(ex.alternatives().isEmpty());
        assertEquals(RoundingMode.EXACT, ex.roundingMode());
        assertTrue(ex.messages().isEmpty());
    }
}
