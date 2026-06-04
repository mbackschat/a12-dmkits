package io.github.mbackschat.a12.rulekit.dsl;

/** A computed date operand — {@link Today} or an {@link DateShift add} result — comparable as a {@link DateOperand}. */
public record DateExpr(Calculation expression) implements DateOperand {
}
