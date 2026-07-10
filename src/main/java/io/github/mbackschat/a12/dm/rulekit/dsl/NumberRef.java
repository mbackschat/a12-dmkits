package io.github.mbackschat.a12.dm.rulekit.dsl;

import java.math.BigDecimal;

/**
 * A typed handle to a number field. Only number fields get the comparison builders, so a
 * type-illegal ordering can't be written (§5b). The declared {@code scale} (decimal places) is read
 * from the model and will feed the field-vs-field scale pre-check (D13) once comparisons take field
 * operands. All comparisons delegate to {@link NumberExpr} — one implementation, every overload
 * (long / scale-faithful String / BigDecimal).
 */
public record NumberRef(FieldRef field, int scale, boolean correlated) {

    public NumberRef(FieldRef field, int scale) {
        this(field, scale, false);
    }

    /**
     * Marks this reference as the {@code $} outer-iteration correlation ({@code [$path]}) — it binds
     * to the enclosing rule iteration. Legal only on a reference used inside a {@code Having} filter
     * (D13); the canonical form is {@code inner.eq(outer.outer())} (or {@code outer.outer().eq(inner)}).
     */
    public NumberRef outer() {
        return new NumberRef(field, scale, true);
    }

    public Condition filled() {
        return field.filled();
    }

    public Condition notFilled() {
        return field.notFilled();
    }

    public FieldValue value() {
        return new FieldValue(field, correlated);
    }

    /** This field's value as a comparable numeric expression. */
    public NumberExpr asNumber() {
        return new NumberExpr(value());
    }

    public Condition lt(long literal) {
        return asNumber().lt(literal);
    }

    public Condition lt(String literal) {
        return asNumber().lt(literal);
    }

    public Condition lt(BigDecimal literal) {
        return asNumber().lt(literal);
    }

    public Condition le(long literal) {
        return asNumber().le(literal);
    }

    public Condition le(String literal) {
        return asNumber().le(literal);
    }

    public Condition le(BigDecimal literal) {
        return asNumber().le(literal);
    }

    public Condition gt(long literal) {
        return asNumber().gt(literal);
    }

    public Condition gt(String literal) {
        return asNumber().gt(literal);
    }

    public Condition gt(BigDecimal literal) {
        return asNumber().gt(literal);
    }

    public Condition ge(long literal) {
        return asNumber().ge(literal);
    }

    public Condition ge(String literal) {
        return asNumber().ge(literal);
    }

    public Condition ge(BigDecimal literal) {
        return asNumber().ge(literal);
    }

    public Condition eq(long literal) {
        return asNumber().eq(literal);
    }

    public Condition eq(String literal) {
        return asNumber().eq(literal);
    }

    public Condition eq(BigDecimal literal) {
        return asNumber().eq(literal);
    }

    public Condition ne(long literal) {
        return asNumber().ne(literal);
    }

    public Condition ne(String literal) {
        return asNumber().ne(literal);
    }

    public Condition ne(BigDecimal literal) {
        return asNumber().ne(literal);
    }

    /**
     * {@code [field For "indexValue"]} — the semantic index (slice 7f): this field's value in the
     * repetition whose index field equals {@code indexValue}. Comparable as a number expression.
     * The enclosing repeatable group must declare an {@code indexFieldName} (else the kernel reports
     * {@code MVK_NO_INDEX_FIELD} — kernel-delegated).
     */
    public NumberExpr forIndex(String indexValue) {
        return new NumberExpr(new SemanticIndexValue(field, indexValue));
    }

    /**
     * {@code [field For keyField]} — the FIELD-KEYED semantic index: this field's value in the repetition
     * whose index field equals the CURRENT value of {@code keyField} (the kernel's "the index value can be a
     * constant or a Field" form; the constant-key peer is {@link #forIndex(String)}). DEV-API-GAPS DG8.
     */
    public NumberExpr forIndex(FieldRef keyField) {
        return new NumberExpr(new SemanticIndexFieldValue(field, keyField));
    }

    /** This field's indexed cell as a PRESENCE handle — {@code indexedCell(key).filled()}; see {@link FieldRef#indexedCell(String)}. */
    public IndexedCell indexedCell(String indexValue) {
        return field.indexedCell(indexValue);
    }

    /** The field-keyed peer — {@link FieldRef#indexedCell(FieldRef)}. */
    public IndexedCell indexedCell(FieldRef keyField) {
        return field.indexedCell(keyField);
    }

    // --- field-vs-field comparison (finding 18) --------------------------------------------------
    // Ordering ignores scale; == / != require equal declared scale (MVK_INVALID_COMPARE_DEC_PLACES,
    // pre-checked here — verified 2026-06-04, the D13 gate made cheap since rulekit knows both scales).

    public Condition lt(NumberRef other) {
        return fieldCmp(CompareOp.LT, other);
    }

    public Condition le(NumberRef other) {
        return fieldCmp(CompareOp.LE, other);
    }

    public Condition gt(NumberRef other) {
        return fieldCmp(CompareOp.GT, other);
    }

    public Condition ge(NumberRef other) {
        return fieldCmp(CompareOp.GE, other);
    }

    public Condition eq(NumberRef other) {
        requireSameScale(other);
        return fieldCmp(CompareOp.EQ, other);
    }

    public Condition ne(NumberRef other) {
        requireSameScale(other);
        return fieldCmp(CompareOp.NE, other);
    }

    /**
     * Null-safe field agreement: both filled and equal, <em>or</em> both empty — renders
     * {@code (AllFieldsFilled(a,b) And [a]==[b]) Or NoFieldFilled(a,b)}. Inherits {@link #eq(NumberRef)}'s
     * equal-scale gate. The string peer is {@link StringRef#agreesWith(StringRef)} (study §3.4a/§3.6).
     */
    public Condition agreesWith(NumberRef other) {
        return Dsl.allFieldsFilled(field, other.field).and(eq(other))
                .or(Dsl.noFieldFilled(field, other.field));
    }

    private Condition fieldCmp(CompareOp op, NumberRef other) {
        return new ComparisonCondition(value(), op, other.value());
    }

    /** {@code [field] DiffersWithToleranceRange{N} [other]} — unequal within a tolerance (see {@link NumberExpr#differsWithTolerance}). */
    public Condition differsWithTolerance(Tolerance t, NumberRef other) {
        return asNumber().differsWithTolerance(t, other.asNumber());
    }

    // --- field-vs-expression comparison (e.g. [Quantity] < Sum(Items*/Count Having ...)) ----------

    public Condition lt(NumberExpr other) {
        return asNumber().lt(other);
    }

    public Condition le(NumberExpr other) {
        return asNumber().le(other);
    }

    public Condition gt(NumberExpr other) {
        return asNumber().gt(other);
    }

    public Condition ge(NumberExpr other) {
        return asNumber().ge(other);
    }

    public Condition eq(NumberExpr other) {
        return asNumber().eq(other);
    }

    public Condition ne(NumberExpr other) {
        return asNumber().ne(other);
    }

    private void requireSameScale(NumberRef other) {
        if (scale != other.scale) {
            throw new IllegalArgumentException("cannot compare number fields of different scale with == / !="
                    + " (" + field.path() + " scale " + scale + " vs " + other.field.path() + " scale "
                    + other.scale + ") — MVK_INVALID_COMPARE_DEC_PLACES; ordering (< <= > >=) is exempt");
        }
    }

    /**
     * {@code FieldValueIncludedInValueList(field, n1, …)} — membership in a literal list. The
     * {@code long} signature is the kernel law: value-list numbers are integer literals only
     * (a decimal is a grammar-level parse error — KERNEL-FINDINGS).
     */
    public Condition in(long first, long... rest) {
        return new ValueListCondition(field, true, integerValues(first, rest));
    }

    /** {@code FieldValueNotIncludedInValueList(field, n1, …)} — integer literals only (see {@link #in}). */
    public Condition notIn(long first, long... rest) {
        return new ValueListCondition(field, false, integerValues(first, rest));
    }

    /**
     * {@code FieldValueIncludedInValueList(field, [f1], …)} — membership against other NUMBER FIELDS'
     * values (the kernel allows field-reference entries in the value list, not only literals).
     * DEV-API-GAPS DG10.
     */
    public Condition in(NumberRef first, NumberRef... rest) {
        return new ValueListCondition(field, true, fieldValues(first, rest));
    }

    /** {@code FieldValueNotIncludedInValueList(field, [f1], …)} — field-reference entries (see {@link #in(NumberRef, NumberRef...)}). */
    public Condition notIn(NumberRef first, NumberRef... rest) {
        return new ValueListCondition(field, false, fieldValues(first, rest));
    }

    private static java.util.List<Calculation> integerValues(long first, long... rest) {
        var values = new java.util.ArrayList<Calculation>();
        values.add(NumberLiteral.of(first));
        for (long v : rest) {
            values.add(NumberLiteral.of(v));
        }
        return values;
    }

    private static java.util.List<Calculation> fieldValues(NumberRef first, NumberRef... rest) {
        var values = new java.util.ArrayList<Calculation>();
        values.add(first.value());
        for (NumberRef r : rest) {
            values.add(r.value());
        }
        return values;
    }
}
