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

    /**
     * {@code [field For "indexValue"]} — the semantic index: this field's value in the repetition whose
     * index field equals {@code indexValue}, as a comparable time. The enclosing repeatable group must
     * declare an {@code indexFieldName} (else {@code MVK_NO_INDEX_FIELD}). (DEV-API-GAPS DG15.)
     */
    public TimeExpr forIndex(String indexValue) {
        return new TimeExpr(new SemanticIndexValue(field, indexValue));
    }

    /** {@code [field For keyField]} — the field-keyed semantic index (the current value of {@code keyField}). */
    public TimeExpr forIndex(FieldRef keyField) {
        return new TimeExpr(new SemanticIndexFieldValue(field, keyField));
    }
}
