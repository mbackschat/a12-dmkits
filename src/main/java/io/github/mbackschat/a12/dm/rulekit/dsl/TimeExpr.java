package io.github.mbackschat.a12.dm.rulekit.dsl;

/** A computed time-of-day operand — the {@link Dsl#timeFromDateTime} of a date-time — comparable as a {@link TimeOperand}. */
public record TimeExpr(Calculation expression) implements TimeOperand {

    /** {@code FieldFilled([Field For key])} — the presence of this indexed cell; valid only on a {@code forIndex(...)} result. */
    public Condition filled() {
        return IndexedPresenceCondition.of(expression, true);
    }

    /** {@code FieldNotFilled([Field For key])} — the absence of this indexed cell; valid only on a {@code forIndex(...)} result. */
    public Condition notFilled() {
        return IndexedPresenceCondition.of(expression, false);
    }
}
