package io.github.mbackschat.a12.dm.rulekit.dsl;

/**
 * A typed handle to a date-time field — the {@link TemporalOperand source} a date-time is decomposed
 * from: its date part ({@link Dsl#dateFromDateTime}), its time part ({@link Dsl#timeFromDateTime}),
 * or any numeric component ({@link Dsl#yearFromDate}, {@link Dsl#hoursFromTime}, …). Obtain from
 * {@link BoundModel#dateTime(String)}. rulekit does not yet model date-time <em>comparison</em>
 * directly (no corpus consumer) — decompose to a date/time/number first, then compare that.
 */
public record DateTimeRef(FieldRef field, boolean correlated) implements TemporalOperand {

    public DateTimeRef(FieldRef field) {
        this(field, false);
    }

    /**
     * Marks this reference as the {@code $} outer-iteration correlation ({@code [$path]}) — it binds
     * to the enclosing rule iteration. Legal only inside a {@code Having} filter (D13).
     */
    public DateTimeRef outer() {
        return new DateTimeRef(field, true);
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
