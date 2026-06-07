package io.github.mbackschat.a12.dm.rulekit.dsl;

import io.github.mbackschat.a12.dm.core.ValueKind;

/**
 * A time-of-day constant — {@code [PickupTime] > "17:00:00"}. The kernel parses a time constant as a
 * temporal constant (its {@code IConstantOperation} carries type {@code DATE}); the {@code value} is the
 * {@code HH:mm:ss} text, carried verbatim and rendered quoted. Like the other literals it carries no
 * operator-id. Wrap in a {@link TimeExpr} to compare it as a {@link TimeOperand}.
 */
public record TimeLiteral(String value) implements Calculation {

    @Override
    public ValueKind kind() {
        return ValueKind.TIME;
    }
}
