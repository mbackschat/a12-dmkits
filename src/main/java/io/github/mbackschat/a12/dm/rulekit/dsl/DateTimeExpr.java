package io.github.mbackschat.a12.dm.rulekit.dsl;

/**
 * A computed date-time operand — a {@code DateTime(date, time)} construction ({@link DateTimeConstruction})
 * or a sub-day {@link DateShift shift} ({@code AddHours(dt, n)}) — comparable as a {@link DateTimeOperand}.
 */
public record DateTimeExpr(Calculation expression) implements DateTimeOperand {

    /** {@code FieldFilled([Field For key])} — the presence of this indexed cell; valid only on a {@code forIndex(...)} result. */
    public Condition filled() {
        return IndexedPresenceCondition.of(expression, true);
    }

    /** {@code FieldNotFilled([Field For key])} — the absence of this indexed cell; valid only on a {@code forIndex(...)} result. */
    public Condition notFilled() {
        return IndexedPresenceCondition.of(expression, false);
    }
}
