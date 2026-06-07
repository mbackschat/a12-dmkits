package io.github.mbackschat.a12.dm.rulekit.dsl;

/** A computed time-of-day operand — the {@link Dsl#timeFromDateTime} of a date-time — comparable as a {@link TimeOperand}. */
public record TimeExpr(Calculation expression) implements TimeOperand {
}
