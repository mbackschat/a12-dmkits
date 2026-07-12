package io.github.mbackschat.a12.dm.rulekit.dsl;

/**
 * A computed date-time operand — a {@code DateTime(date, time)} construction ({@link DateTimeConstruction})
 * or a sub-day {@link DateShift shift} ({@code AddHours(dt, n)}) — comparable as a {@link DateTimeOperand}.
 */
public record DateTimeExpr(Calculation expression) implements DateTimeOperand {
}
