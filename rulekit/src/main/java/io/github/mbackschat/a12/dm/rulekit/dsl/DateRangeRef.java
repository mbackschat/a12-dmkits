package io.github.mbackschat.a12.dm.rulekit.dsl;

/**
 * A typed handle to a date-range field (an interval of two dates, §6) — the source of
 * {@link Dsl#startOfDateRange}/{@link Dsl#endOfDateRange}, which yield its bound dates, and a
 * {@link DateRangeOperand} for equality/inequality. Obtain from {@link BoundModel#dateRange(String)}.
 */
public record DateRangeRef(FieldRef field, boolean correlated) implements DateRangeOperand {

    public DateRangeRef(FieldRef field) {
        this(field, false);
    }

    /**
     * Marks this reference as the {@code $} outer-iteration correlation ({@code [$path]}) — it binds
     * to the enclosing rule iteration. Legal only inside a {@code Having} filter (D13).
     */
    public DateRangeRef outer() {
        return new DateRangeRef(field, true);
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
