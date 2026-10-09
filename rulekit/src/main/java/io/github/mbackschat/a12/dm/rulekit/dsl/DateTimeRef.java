package io.github.mbackschat.a12.dm.rulekit.dsl;

/**
 * A typed handle to a date-time field — the {@link DateTimeOperand source} a date-time is decomposed
 * from: its date part ({@link Dsl#dateFromDateTime}), its time part ({@link Dsl#timeFromDateTime}),
 * or any numeric component ({@link Dsl#yearFromDate}, {@link Dsl#hoursFromTime}, …). Obtain from
 * {@link BoundModel#dateTime(String)}. As a {@link DateTimeOperand} it also compares directly to
 * another date-time value (a field or a {@code DateTime(...)} construction) — not to a bare string
 * literal (the kernel rejects that).
 */
public record DateTimeRef(FieldRef field, boolean correlated) implements DateTimeOperand {

    /** The marker lives on {@link #field()}, so every node built from it reads the rule's row; see {@link FieldRef#marked}. */
    public DateTimeRef {
        correlated |= field.correlated();
        field = field.marked(correlated);
    }

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

    /**
     * Null-safe field agreement: both filled and equal, <em>or</em> both empty — the date-time peer of
     * {@link DateRef#agreesWith(DateRef)}, using date-time equality underneath (D8).
     */
    public Condition agreesWith(DateTimeRef other) {
        return Dsl.allFieldsFilled(field, other.field).and(eq(other))
                .or(Dsl.noFieldFilled(field, other.field));
    }

    /**
     * {@code [field For "indexValue"]} — the semantic index: this field's value in the repetition whose
     * index field equals {@code indexValue}, as a comparable date-time. The enclosing repeatable group must
     * declare an {@code indexFieldName} (else {@code MVK_NO_INDEX_FIELD}). (DEV-API-GAPS DG15.)
     */
    public DateTimeExpr forIndex(String indexValue) {
        return new DateTimeExpr(new SemanticIndexValue(field, indexValue));
    }

    /** {@code [field For keyField]} — the field-keyed semantic index (the current value of {@code keyField}). */
    public DateTimeExpr forIndex(FieldRef keyField) {
        return new DateTimeExpr(new SemanticIndexFieldValue(field, keyField));
    }

    /** This field's semantic index as a PRESENCE handle — {@code semanticIndex(key).filled()}; see {@link FieldRef#semanticIndex(String)}. */
    public SemanticIndex semanticIndex(String indexValue) {
        return field.semanticIndex(indexValue);
    }

    /** The field-keyed peer — {@link FieldRef#semanticIndex(FieldRef)}. */
    public SemanticIndex semanticIndex(FieldRef keyField) {
        return field.semanticIndex(keyField);
    }
}
