package io.github.mbackschat.a12.dm.rulekit.dsl;

/**
 * A typed handle to a time-of-day field — orderable time comparisons (§5b) via {@link TimeOperand},
 * and presence checks. Obtain from {@link BoundModel#time(String)}. Time <em>constant literals</em>
 * are format-finicky and deferred (cf. {@link DateRef}); compare to another time field or a
 * {@link Dsl#timeFromDateTime} instead.
 */
public record TimeRef(FieldRef field, boolean correlated) implements TimeOperand {

    public TimeRef(FieldRef field) {
        this(field, false);
    }

    /**
     * Marks this reference as the {@code $} outer-iteration correlation ({@code [$path]}) — it binds
     * to the enclosing rule iteration. Legal only inside a {@code Having} filter (D13).
     */
    public TimeRef outer() {
        return new TimeRef(field, true);
    }

    @Override
    public Calculation expression() {
        return new FieldValue(field, correlated);
    }

    public Condition filled() {
        return field.filled();
    }

    public Condition notFilled() {
        return field.notFilled();
    }
}
