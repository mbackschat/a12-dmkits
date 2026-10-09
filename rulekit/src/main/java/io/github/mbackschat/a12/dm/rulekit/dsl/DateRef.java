package io.github.mbackschat.a12.dm.rulekit.dsl;

/**
 * A typed handle to a date field — orderable date comparisons (§5b) via {@link DateOperand}, and
 * presence checks. Obtain from {@link BoundModel#date(String)}. Date <em>constant literals</em>
 * (and {@code Date(...)} construction) are format/fragment-finicky and deferred (KERNEL-FINDINGS §6);
 * compare to another date field or {@link Dsl#today()} instead.
 */
public record DateRef(FieldRef field, boolean correlated) implements DateOperand {

    /** The marker lives on {@link #field()}, so every node built from it reads the rule's row; see {@link FieldRef#marked}. */
    public DateRef {
        correlated |= field.correlated();
        field = field.marked(correlated);
    }

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

    /**
     * Null-safe field agreement: both filled and equal, <em>or</em> both empty — the temporal peer of
     * {@link StringRef#agreesWith(StringRef)} / {@link NumberRef#agreesWith(NumberRef)} (study §3.4a/§3.6),
     * using date equality underneath (D8).
     */
    public Condition agreesWith(DateRef other) {
        return Dsl.allFieldsFilled(field, other.field).and(eq(other))
                .or(Dsl.noFieldFilled(field, other.field));
    }

    /**
     * {@code [field For "indexValue"]} — the semantic index: this field's value in the repetition whose
     * index field equals {@code indexValue}, as a comparable date. The enclosing repeatable group must
     * declare an {@code indexFieldName} (else {@code MVK_NO_INDEX_FIELD}). (DEV-API-GAPS DG15.)
     */
    public DateExpr forIndex(String indexValue) {
        return new DateExpr(new SemanticIndexValue(field, indexValue));
    }

    /** {@code [field For keyField]} — the field-keyed semantic index (the current value of {@code keyField}). */
    public DateExpr forIndex(FieldRef keyField) {
        return new DateExpr(new SemanticIndexFieldValue(field, keyField));
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
