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

    /**
     * {@code [range For "indexValue"]} — the semantic index: this range's value in the repetition whose
     * index field equals {@code indexValue}. The enclosing repeatable group must declare an
     * {@code indexFieldName} (else {@code MVK_NO_INDEX_FIELD}).
     */
    public DateRangeIndexedValue forIndex(String indexValue) {
        return new DateRangeIndexedValue(new SemanticIndexValue(field, indexValue));
    }

    /** {@code [range For keyField]} — the field-keyed semantic index (the current value of {@code keyField}). */
    public DateRangeIndexedValue forIndex(FieldRef keyField) {
        return new DateRangeIndexedValue(new SemanticIndexFieldValue(field, keyField));
    }

    public Condition filled() {
        return field.filled();
    }

    public Condition notFilled() {
        return field.notFilled();
    }
}
