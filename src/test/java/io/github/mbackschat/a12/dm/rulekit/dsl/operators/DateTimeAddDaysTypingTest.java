package io.github.mbackschat.a12.dm.rulekit.dsl.operators;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.Fixtures;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.DateTimeExpr;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer;

class DateTimeAddDaysTypingTest {

    private static final BoundModel MODEL = Fixtures.order();

    @Test
    void addDaysPreservesDateTimeTypeForConstantAndFieldAmounts() {
        var started = MODEL.dateTime("/Order/ScheduledAt");
        var completed = MODEL.dateTime("/Order/CompletedAt");

        DateTimeExpr byConstant = Dsl.addDays(started, 1);
        DateTimeExpr byField = Dsl.addDays(started, MODEL.num("/Order/BackorderQuantity").asNumber());

        var renderer = new DslRenderer();
        assertEquals("AddDays(/Order/ScheduledAt, 1) == [/Order/CompletedAt]",
                renderer.render(byConstant.eq(completed)));
        assertEquals("AddDays(/Order/ScheduledAt, [/Order/BackorderQuantity]) == [/Order/CompletedAt]",
                renderer.render(byField.eq(completed)));
    }
}
