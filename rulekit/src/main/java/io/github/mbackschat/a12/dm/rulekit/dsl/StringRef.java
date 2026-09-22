package io.github.mbackschat.a12.dm.rulekit.dsl;

import io.github.mbackschat.a12.dm.core.KernelPatternPolicy;
import java.util.ArrayList;
import java.util.List;

/**
 * A typed handle to a string field. Offers string equality (the kernel has no ordering on strings —
 * §5b) and {@code Length(...)} as a numeric expression. Obtain from {@link BoundModel#str(String)}.
 */
public record StringRef(FieldRef field, boolean correlated) {

    public StringRef(FieldRef field) {
        this(field, false);
    }

    /**
     * Marks this reference as the {@code $} outer-iteration correlation ({@code [$path]}) — it binds
     * to the enclosing rule iteration. Legal only on a reference used inside a {@code Having} filter
     * (D13); the canonical form is {@code inner.eq(outer.outer())}.
     */
    public StringRef outer() {
        return new StringRef(field, true);
    }

    private FieldValue value() {
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
     * index field equals {@code indexValue}, as a comparable string expression. The enclosing repeatable
     * group must declare an {@code indexFieldName} (else {@code MVK_NO_INDEX_FIELD}). (DEV-API-GAPS DG15.)
     */
    public StringExpr forIndex(String indexValue) {
        return new StringExpr(new SemanticIndexValue(field, indexValue));
    }

    /** {@code [field For keyField]} — the field-keyed semantic index (the current value of {@code keyField}). */
    public StringExpr forIndex(FieldRef keyField) {
        return new StringExpr(new SemanticIndexFieldValue(field, keyField));
    }

    /** This field's semantic index as a PRESENCE handle — {@code semanticIndex(key).filled()}; see {@link FieldRef#semanticIndex(String)}. */
    public SemanticIndex semanticIndex(String indexValue) {
        return field.semanticIndex(indexValue);
    }

    /** The field-keyed peer — {@link FieldRef#semanticIndex(FieldRef)}. */
    public SemanticIndex semanticIndex(FieldRef keyField) {
        return field.semanticIndex(keyField);
    }

    public Condition eq(String literal) {
        return new ComparisonCondition(value(), CompareOp.EQ, new StringLiteral(literal));
    }

    public Condition ne(String literal) {
        return new ComparisonCondition(value(), CompareOp.NE, new StringLiteral(literal));
    }

    /** Field-vs-field equality {@code [this] == [other]} — strings have no scale, so no gate. */
    public Condition eq(StringRef other) {
        return new ComparisonCondition(value(), CompareOp.EQ, other.value());
    }

    public Condition ne(StringRef other) {
        return new ComparisonCondition(value(), CompareOp.NE, other.value());
    }

    /**
     * Null-safe field agreement: both filled and equal, <em>or</em> both empty — renders
     * {@code (AllFieldsFilled(a,b) And [a]==[b]) Or NoFieldFilled(a,b)}. The single most repeated idiom in
     * field-mirroring rules (study §3.4a/§3.6), shipped so it is written — and its polarity reviewed — once.
     */
    public Condition agreesWith(StringRef other) {
        return Dsl.allFieldsFilled(field, other.field).and(eq(other))
                .or(Dsl.noFieldFilled(field, other.field));
    }

    /** This field as a string expression — the entry point for concatenation ({@code asString().plus(…)}). */
    public StringExpr asString() {
        return new StringExpr(field.value());
    }

    /** {@code [this] == <string expression>}, e.g. against a {@code [First] + " " + [Last]} concatenation. */
    public Condition eq(StringExpr other) {
        return new ComparisonCondition(field.value(), CompareOp.EQ, other.expression());
    }

    /** {@code [this] != <string expression>} — the consistency form (stored name vs a derived one). */
    public Condition ne(StringExpr other) {
        return new ComparisonCondition(field.value(), CompareOp.NE, other.expression());
    }

    /** {@code Length(field)} as a numeric expression for comparison. */
    public NumberExpr length() {
        return new NumberExpr(new LengthOf(field));
    }

    /** {@code FieldValueIncludedInValueList(field, "v1", …)} — membership in a literal list. */
    public Condition in(String first, String... rest) {
        return new ValueListCondition(field, true, stringValues(first, rest));
    }

    /** {@code FieldValueNotIncludedInValueList(field, "v1", …)}. */
    public Condition notIn(String first, String... rest) {
        return new ValueListCondition(field, false, stringValues(first, rest));
    }

    /**
     * {@code FieldValueIncludedInValueList(field, [f1], …)} — membership against other STRING FIELDS'
     * values (the kernel allows field-reference entries, not only literals). DEV-API-GAPS DG10.
     */
    public Condition in(StringRef first, StringRef... rest) {
        return new ValueListCondition(field, true, fieldValues(first, rest));
    }

    /** {@code FieldValueNotIncludedInValueList(field, [f1], …)} — field-reference entries (see {@link #in(StringRef, StringRef...)}). */
    public Condition notIn(StringRef first, StringRef... rest) {
        return new ValueListCondition(field, false, fieldValues(first, rest));
    }

    static List<Calculation> fieldValues(StringRef first, StringRef... rest) {
        var values = new ArrayList<Calculation>();
        values.add(first.value());
        for (StringRef r : rest) {
            values.add(r.value());
        }
        return values;
    }

    static List<Calculation> stringValues(String first, String... rest) {
        var values = new ArrayList<Calculation>();
        values.add(new StringLiteral(first));
        for (String v : rest) {
            values.add(new StringLiteral(v));
        }
        return values;
    }

    /** {@code [field] PatternMatched "regex"} — anchored whole-value match (KERNEL-SEMANTICS §7). */
    public Condition matches(String regex) {
        return new PatternCondition(field, true, checkRegex(regex));
    }

    /** {@code [field] PatternViolated "regex"} — anchored whole-value non-match. */
    public Condition violates(String regex) {
        return new PatternCondition(field, false, checkRegex(regex));
    }

    /**
     * Fail-fast check for {@code MVK_INVALID_PATTERN}: Java syntax followed by the kernel 30.8.x
     * admission restrictions. Passing them is kernel admission, not a JavaScript-portability guarantee.
     */
    private static String checkRegex(String regex) {
        return KernelPatternPolicy.requireAccepted(regex);
    }
}
