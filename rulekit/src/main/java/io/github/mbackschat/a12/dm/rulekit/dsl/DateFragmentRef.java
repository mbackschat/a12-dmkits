package io.github.mbackschat.a12.dm.rulekit.dsl;

/**
 * A typed handle to a date-fragment field (e.g. a year-month partial date, §6). It is a full
 * {@link DateOperand}: the kernel collapses a date fragment into a plain {@code Date} for
 * parsing/checking/rendering/comparison (KERNEL-FINDINGS §6), so a fragment compares like a date field
 * ({@code <, <=, >, >=, ==, !=}) against a date field, another fragment, {@code Date(...)},
 * {@link Dsl#today() Today}, or a date expression. It is also usable as the {@link DateOperand
 * source} of a component extraction ({@link Dsl#monthFromDate}, {@link Dsl#yearFromDate}). Obtain from
 * {@link BoundModel#dateFragment(String)}. The kernel enforces one extra rule at validation time — two
 * date values are comparable only if both carry a year or neither does (the year-class partition, §6).
 */
public record DateFragmentRef(FieldRef field, boolean correlated) implements DateOperand {

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
