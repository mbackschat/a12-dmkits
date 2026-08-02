package io.github.mbackschat.a12.dm.rulekit.dsl;

import io.github.mbackschat.a12.dm.core.ValueKind;

/**
 * {@code Now} — the current date-time (DE {@code Jetzt}); the date-time sibling of {@link Today}. It
 * compares to a date-time field and orders against a date field on their common timeline (the date is
 * model-zone midnight; {@code Now} retains its time). Like {@code Today} it is a point-in-time constant —
 * discouraged with {@code ==} and unstable in computations (the kernel's call, F4/N7).
 */
public record Now() implements Calculation {

    public String operatorId() {
        return "Now";
    }

    @Override
    public ValueKind kind() {
        return ValueKind.DATE_TIME;
    }
}
