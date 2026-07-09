package io.github.mbackschat.a12.dm.rulekit.dsl;

/** A computed date operand — {@link Today} or an {@link DateShift add} result — comparable as a {@link DateOperand}. */
public record DateExpr(Calculation expression) implements DateOperand {

    /** {@code FieldFilled([Field For key])} — the presence of this indexed cell; valid only on a {@code forIndex(...)} result. */
    public Condition filled() {
        return IndexedPresenceCondition.of(expression, true);
    }

    /** {@code FieldNotFilled([Field For key])} — the absence of this indexed cell; valid only on a {@code forIndex(...)} result. */
    public Condition notFilled() {
        return IndexedPresenceCondition.of(expression, false);
    }
}
