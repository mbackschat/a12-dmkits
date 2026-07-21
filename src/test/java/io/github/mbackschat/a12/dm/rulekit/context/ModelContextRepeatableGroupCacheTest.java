package io.github.mbackschat.a12.dm.rulekit.context;

import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.de;
import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.en;
import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.fieldFilled;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.Fixtures;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Computation;
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberLiteral;
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberRef;

/** Locks the model-static repeatable-group index shared by every explanation path. */
class ModelContextRepeatableGroupCacheTest {

    @Test
    void scansGroupsOnceAcrossRuleAndComputationPreconditionExplanations() {
        BoundModel model = Fixtures.subscription();
        AtomicInteger groupScans = new AtomicInteger();
        ModelContext context = new ModelContext(model, () -> {
            groupScans.incrementAndGet();
            return model.groups();
        });

        NumberRef base = model.num("/Subscription/Billing/BaseFee");
        assertFalse(context.explain(base.gt(0)).iterates(), "the non-repeatable rule still takes the fallback path");

        Computation computation = model.computation(model.num("/Subscription/Billing/EffectiveFee"))
                .name("EffectiveFeeCacheProbe")
                .commonPrecondition(fieldFilled(base.field()))
                .when(base.ge(100)).calc(base.value())
                .when(base.lt(100)).calc(NumberLiteral.of("0.00"))
                .message(en("Effective fee follows the tiers."), de("Effektivgebühr nach Stufen."))
                .build();
        context.explainComputation(computation);

        assertEquals(1, groupScans.get(),
                "one ModelContext must index repeatable groups once, not rescan them per rule or precondition");
    }
}
