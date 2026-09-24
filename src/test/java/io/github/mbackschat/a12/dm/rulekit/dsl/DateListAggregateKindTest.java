package io.github.mbackschat.a12.dm.rulekit.dsl;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.Fixtures;
import io.github.mbackschat.a12.dm.core.KernelCodeRefusal;
import io.github.mbackschat.a12.dm.core.ValueKind;

/**
 * The {@code kind} a {@link DateListAggregate} is built with types the node for its caller and is never rendered,
 * so a non-temporal one is a misuse of the constructor rather than anything A12 could report. Its refusal
 * therefore carries no kernel code.
 */
class DateListAggregateKindTest {

    @Test
    void aNonTemporalKindIsRefusedWithoutAKernelCode() {
        BoundModel order = Fixtures.order();
        List<AggregateOperand> operands = List.of(order.field("/Order/CustomerName"), order.field("/Order/ProductCode"));
        IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                () -> new DateListAggregate(DateListAggregate.Mode.LATEST, operands, ValueKind.NUMBER));
        assertFalse(refusal instanceof KernelCodeRefusal, refusal::getMessage);
    }
}
