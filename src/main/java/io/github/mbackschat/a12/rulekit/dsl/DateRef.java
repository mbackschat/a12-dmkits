package io.github.mbackschat.a12.rulekit.dsl;

/**
 * A typed handle to a date field — orderable date comparisons (§5b) via {@link DateOperand}, and
 * presence checks. Obtain from {@link BoundModel#date(String)}. Date <em>constant literals</em>
 * (and {@code Date(...)} construction) are format/fragment-finicky and deferred (KERNEL-FINDINGS §6);
 * compare to another date field or {@link Dsl#today()} instead.
 */
public record DateRef(FieldRef field) implements DateOperand {

    @Override
    public Calculation expression() {
        return field.value();
    }

    public Condition filled() {
        return field.filled();
    }

    public Condition notFilled() {
        return field.notFilled();
    }
}
