package io.github.mbackschat.a12.dm.rulekit.dsl;

/**
 * A typed handle to a date-time field — the {@link TemporalOperand source} a date-time is decomposed
 * from: its date part ({@link Dsl#dateFromDateTime}), its time part ({@link Dsl#timeFromDateTime}),
 * or any numeric component ({@link Dsl#yearFromDate}, {@link Dsl#hoursFromTime}, …). Obtain from
 * {@link BoundModel#dateTime(String)}. As a {@link DateTimeOperand} it also compares directly to
 * another date-time value (a field or a {@code DateTime(...)} construction) — not to a bare string
 * literal (the kernel rejects that).
 */
public record DateTimeRef(FieldRef field, boolean correlated) implements DateTimeOperand {

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
