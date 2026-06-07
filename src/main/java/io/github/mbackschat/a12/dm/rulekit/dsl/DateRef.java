package io.github.mbackschat.a12.dm.rulekit.dsl;

/**
 * A typed handle to a date field — orderable date comparisons (§5b) via {@link DateOperand}, and
 * presence checks. Obtain from {@link BoundModel#date(String)}. Date <em>constant literals</em>
 * (and {@code Date(...)} construction) are format/fragment-finicky and deferred (KERNEL-FINDINGS §6);
 * compare to another date field or {@link Dsl#today()} instead.
 */
public record DateRef(FieldRef field, boolean correlated) implements DateOperand {

    public DateRef(FieldRef field) {
        this(field, false);
    }

    /**
     * Marks this reference as the {@code $} outer-iteration correlation ({@code [$path]}) — it binds
     * to the enclosing rule iteration. Legal only inside a {@code Having} filter (D13).
     */
    public DateRef outer() {
        return new DateRef(field, true);
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
