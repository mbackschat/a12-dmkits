package io.github.mbackschat.a12.dm.rulekit.dsl;

/**
 * A typed handle to a date-range field (an interval of two dates, §6) — the source of
 * {@link Dsl#startOfDateRange}/{@link Dsl#endOfDateRange}, which yield its bound dates. Obtain from
 * {@link BoundModel#dateRange(String)}. Like {@link DateTimeRef} it is a source-only kind: rulekit
 * does not model date-range comparison directly — extract a bound (a date) first, then compare that.
 */
public record DateRangeRef(FieldRef field, boolean correlated) {

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

    Calculation expression() {
        return new FieldValue(field, correlated);
    }

    public Condition filled() {
        return field.filled();
    }

    public Condition notFilled() {
        return field.notFilled();
    }
}
