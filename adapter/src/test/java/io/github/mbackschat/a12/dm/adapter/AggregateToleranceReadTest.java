package io.github.mbackschat.a12.dm.adapter;
import io.github.mbackschat.a12.dm.rulekit.read.ReadResult;

import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.en;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.ConditionLanguage;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer;
import io.github.mbackschat.a12.dm.rulekit.dsl.Rule;
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity;
import io.github.mbackschat.a12.dm.rulekit.dsl.Tolerance;

/**
 * F10 read of an <b>aggregate compared with a tolerance operator</b> — {@code DiffersWithToleranceRange{N}}
 * (kernel {@code NE1}/{@code NE2}/{@code NE5}/{@code NE10}). The tolerance family was handled for a plain
 * field-vs-field comparison but not in the expression paths ({@code numberExprComparison} / {@code
 * exprVsExpr}), so an aggregate with a tolerance op read Opaque ("aggregate comparison operator NE1").
 *
 * <p>Own domain: an order whose {@code Items} line counts should sum to the order {@code Quantity} within 1.
 */
class AggregateToleranceReadTest {

    private static final DslRenderer RENDER = new DslRenderer(ConditionLanguage.EN);

    @Test
    void roundTripsAnAggregateToleranceComparison() {
        BoundModel m = BoundModel.ofJson(TestResources.text("models/order.dm.json"));
        Condition c = m.num("/Order/Quantity").filled()
                .and(Dsl.sum(m.flatten("/Order/Items*/Count"))
                        .differsWithTolerance(Tolerance.RANGE_1, m.num("/Order/Quantity").asNumber()));
        KernelAdapter adapter = KernelAdapter.of(m.add(rule(m, "SumTol", "/Order/Quantity", c)));
        assertTrue(RENDER.render(c).contains("DiffersWithToleranceRange1"), RENDER.render(c));
        assertEquals(RENDER.render(c), RENDER.render(converted(adapter.read("/Order/SumTol"))));
    }

    private static Rule rule(BoundModel m, String name, String errorField, Condition cond) {
        return m.rule(m.group("/Order")).name(name).errorField(m.field(errorField)).when(cond)
                .code("C").severity(Severity.ERROR).message(en("x")).build();
    }

    private static Condition converted(ReadResult result) {
        return assertInstanceOf(ReadResult.Converted.class, result,
                () -> "expected a converted read, got: " + result).condition();
    }
}
