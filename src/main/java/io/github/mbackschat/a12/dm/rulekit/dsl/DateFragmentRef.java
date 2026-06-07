package io.github.mbackschat.a12.dm.rulekit.dsl;

/**
 * A typed handle to a date-fragment field (e.g. a year-month partial date, §6) — usable as the
 * {@link TemporalOperand source} of a component extraction ({@link Dsl#monthFromDate},
 * {@link Dsl#yearFromDate}). Obtain from {@link BoundModel#dateFragment(String)}. Like
 * {@link DateTimeRef} it is a source-only kind: rulekit does not model date-fragment comparison
 * directly (no corpus consumer) — extract a component first, then compare that.
 */
public record DateFragmentRef(FieldRef field, boolean correlated) implements TemporalOperand {

    public DateFragmentRef(FieldRef field) {
        this(field, false);
    }

    /**
     * Marks this reference as the {@code $} outer-iteration correlation ({@code [$path]}) — it binds
     * to the enclosing rule iteration. Legal only inside a {@code Having} filter (D13).
     */
    public DateFragmentRef outer() {
        return new DateFragmentRef(field, true);
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
