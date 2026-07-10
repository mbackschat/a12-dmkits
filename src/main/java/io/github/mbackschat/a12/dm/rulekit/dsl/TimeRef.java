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
     * Null-safe field agreement: both filled and equal, <em>or</em> both empty — the time peer of
     * {@link DateRef#agreesWith(DateRef)}, using time equality underneath (D8).
     */
    public Condition agreesWith(TimeRef other) {
        return Dsl.allFieldsFilled(field, other.field).and(eq(other))
                .or(Dsl.noFieldFilled(field, other.field));
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

    /** This field's indexed cell as a PRESENCE handle — {@code indexedCell(key).filled()}; see {@link FieldRef#indexedCell(String)}. */
    public IndexedCell indexedCell(String indexValue) {
        return field.indexedCell(indexValue);
    }

    /** The field-keyed peer — {@link FieldRef#indexedCell(FieldRef)}. */
    public IndexedCell indexedCell(FieldRef keyField) {
        return field.indexedCell(keyField);
    }
}
