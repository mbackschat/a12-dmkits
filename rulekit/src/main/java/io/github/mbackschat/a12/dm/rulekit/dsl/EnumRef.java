package io.github.mbackschat.a12.dm.rulekit.dsl;

import java.util.List;
import java.util.Map;
import io.github.mbackschat.a12.dm.core.KernelCode;
import io.github.mbackschat.a12.dm.core.Refusals;

/**
 * A typed handle to an enumeration field. Comparisons are by <em>stored value</em> (§5b), and the
 * value is checked against the model's enum domain at build time — {@code is("NOPE")} on a field
 * whose values are {@code BASIC/PRO/ENTERPRISE} fails fast rather than producing text the kernel
 * later rejects. {@link #category(String)} navigates to a model-declared category
 * ({@code [field -> Category]}). Obtain from {@link BoundModel#enumOf(String)}.
 */
public record EnumRef(FieldRef field, List<String> allowedValues, Map<String, List<String>> categories,
        boolean correlated) implements EnumListRef, NumericTextSource {

    /** The marker lives on {@link #field()}, so every node built from it reads the rule's row; see {@link FieldRef#marked}. */
    public EnumRef {
        correlated |= field.correlated();
        field = field.marked(correlated);
        allowedValues = List.copyOf(allowedValues);
        categories = Map.copyOf(categories);
    }

    // --- EnumListRef: a direct enum reference contributes its stored values to a quantifier's union ---

    @Override
    public ValueListOperand listOperand() {
        return field;
    }

    @Override
    public List<String> admissibleValues() {
        return allowedValues;
    }

    public EnumRef(FieldRef field, List<String> allowedValues, Map<String, List<String>> categories) {
        this(field, allowedValues, categories, false);
    }

    /**
     * Marks this reference as the {@code $} outer-iteration correlation ({@code [$path]}) — it binds
     * to the enclosing rule iteration. Legal only inside a {@code Having} filter (D13); the canonical
     * form is {@code inner.is(outer.outer())}.
     */
    public EnumRef outer() {
        return new EnumRef(field, allowedValues, categories, true);
    }

    FieldValue value() {   // package-private: EnumExpr compares against a field's value (DG15 semantic index)
        return new FieldValue(field, correlated);
    }

    /** The category named {@code name} — fails fast if the model declares no such category. catalog:EnumCategory */
    public CategoryRef category(String name) {
        List<String> values = categories.get(name);
        if (values == null) {
            throw Refusals.of(KernelCode.MVK_CATEGORY_UNKOWN, "'" + name + "' is not a category of " + field.path()
                    + " — declared: " + categories.keySet());
        }
        return new CategoryRef(field, name, values);
    }

    /**
     * {@code { [this] } + <text>} — this enumeration joined to text; an enumeration stands in a concatenation only
     * beside text or another enumeration. It is read BRACED, which A12 admits whatever its labels (KERNEL-FINDINGS
     * KF396, KF655). The enumeration contributes its stored value, not a label (KF530). catalog:Concatenation
     */
    public StringExpr plus(StringExpr text) {
        return StringExpr.joined(field.value(), text.expression());
    }

    /** {@code { [this] } + "literal"} — as {@link #plus(StringExpr)}, followed by a string literal. */
    public StringExpr plus(String literal) {
        return plus(new StringExpr(new StringLiteral(literal)));
    }

    /** {@code { [this] } + [text]} — as {@link #plus(StringExpr)}, followed by a text field's value. */
    public StringExpr plus(StringRef text) {
        return plus(text.asString());
    }

    /**
     * {@code { [this] } + { [other] }} — two enumerations joined, each read braced, which A12 admits with or without
     * text around them (KERNEL-FINDINGS KF530); each contributes its stored value.
     */
    public StringExpr plus(EnumRef other) {
        return StringExpr.joined(field.value(), other.field().value());
    }

    public Condition filled() {
        return field.filled();
    }

    public Condition notFilled() {
        return field.notFilled();
    }

    /**
     * {@code [field For "indexValue"]} — the semantic index: this field's value in the repetition whose
     * index field equals {@code indexValue}, as a comparable enum expression. The enclosing repeatable
     * group must declare an {@code indexFieldName} (else {@code MVK_NO_INDEX_FIELD}). (DEV-API-GAPS DG15.)
     */
    public EnumExpr forIndex(String indexValue) {
        return new EnumExpr(new SemanticIndexValue(field, indexValue));
    }

    /** {@code [field For keyField]} — the field-keyed semantic index (the current value of {@code keyField}). */
    public EnumExpr forIndex(FieldRef keyField) {
        return new EnumExpr(new SemanticIndexFieldValue(field, keyField));
    }

    /** This field's semantic index as a PRESENCE handle — {@code semanticIndex(key).filled()}; see {@link FieldRef#semanticIndex(String)}. */
    public SemanticIndex semanticIndex(String indexValue) {
        return field.semanticIndex(indexValue);
    }

    /** The field-keyed peer — {@link FieldRef#semanticIndex(FieldRef)}. */
    public SemanticIndex semanticIndex(FieldRef keyField) {
        return field.semanticIndex(keyField);
    }

    /** {@code [field] == "value"} — enum equality by stored value (domain-checked at build). catalog:Equal */
    public Condition is(String value) {
        return compareTo(CompareOp.EQ, value);
    }

    /** {@code [field] != "value"} — enum inequality by stored value (domain-checked at build). catalog:NotEqual */
    public Condition isNot(String value) {
        return compareTo(CompareOp.NE, value);
    }

    /**
     * Field-vs-field equality {@code [this] == [other]} — compared by stored value. Whether the two
     * enums are type-compatible is the kernel's call (F4/N7), as for {@link StringRef#eq(StringRef)}.
     */
    public Condition is(EnumRef other) {
        return new ComparisonCondition(value(), CompareOp.EQ, other.value());
    }

    public Condition isNot(EnumRef other) {
        return new ComparisonCondition(value(), CompareOp.NE, other.value());
    }

    /**
     * Null-safe field agreement: both filled and equal, <em>or</em> both empty — renders
     * {@code (AllFieldsFilled(a,b) And [a]==[b]) Or NoFieldFilled(a,b)} via {@link #is(EnumRef)}. The enum
     * peer of {@link StringRef#agreesWith(StringRef)} / {@link NumberRef#agreesWith(NumberRef)} (study §3.4a/§3.6).
     */
    public Condition agreesWith(EnumRef other) {
        return Dsl.allFieldsFilled(field, other.field).and(is(other))
                .or(Dsl.noFieldFilled(field, other.field));
    }

    /** {@code [this] == <enum expression>} (e.g. a {@link Dsl#firstFilledEnum}) — compared by stored value. */
    public Condition is(EnumExpr other) {
        return new ComparisonCondition(value(), CompareOp.EQ, other.expression());
    }

    public Condition isNot(EnumExpr other) {
        return new ComparisonCondition(value(), CompareOp.NE, other.expression());
    }

    // --- eq/ne: the equality verbs shared with the number/string/date refs (D1). is/isNot above stay as
    //     the domain-natural enum reading; eq/ne give cross-type uniformity. bool/confirm are the
    //     deliberate exception (their target is a fixed constant, not an arbitrary value) — API-GUIDE §6.

    /** {@code [field] == "value"} — the eq peer of {@link #is(String)}. */
    public Condition eq(String value) {
        return is(value);
    }

    /** {@code [field] != "value"} — the ne peer of {@link #isNot(String)}. */
    public Condition ne(String value) {
        return isNot(value);
    }

    /** {@code [this] == [other]} — the eq peer of {@link #is(EnumRef)}. */
    public Condition eq(EnumRef other) {
        return is(other);
    }

    public Condition ne(EnumRef other) {
        return isNot(other);
    }

    /** {@code [this] == <enum expression>} — the eq peer of {@link #is(EnumExpr)}. */
    public Condition eq(EnumExpr other) {
        return is(other);
    }

    public Condition ne(EnumExpr other) {
        return isNot(other);
    }

    /**
     * {@code [this] == [text]} — the text field against this enumeration's stored value, never its label. A12 admits
     * it whether or not the enumeration carries display labels (KERNEL-FINDINGS KF655, kernel 31.0's A12K-3969).
     */
    public Condition eq(StringRef text) {
        return new ComparisonCondition(value(), CompareOp.EQ, text.value());
    }

    public Condition ne(StringRef text) {
        return new ComparisonCondition(value(), CompareOp.NE, text.value());
    }

    /**
     * The single-comparison carrier cites a DIFFERENT code from the value-list one, measured rather than
     * assumed: the kernel reports {@code MVK_INVALID_STRING_CONSTANT_FOR_ENUM_COMPARISON} here and
     * {@code MVK_INVALID_STRING_CONSTANT_FOR_ENUMERATION} for {@code in}/{@code notIn}
     * ({@code validate.laws.UnbackedClientRefusalLawsTest}), so the two must not share a code however alike
     * the refused input looks.
     */
    private Condition compareTo(CompareOp op, String value) {
        if (!allowedValues.contains(value)) {
            throw Refusals.of(KernelCode.MVK_INVALID_STRING_CONSTANT_FOR_ENUM_COMPARISON,
                    "'" + value + "' is not a value of " + field.path() + " — allowed: " + allowedValues
                            + io.github.mbackschat.a12.dm.core.Suggest.hint(value, allowedValues));
        }
        return new ComparisonCondition(value(), op, new EnumValueLiteral(value));
    }

    /**
     * {@code FieldValueIncludedInValueList(field, "v1", …)} — every value is checked against the
     * enum domain (pre-checks {@code MVK_INVALID_STRING_CONSTANT_FOR_ENUMERATION}).
     */
    public Condition in(String first, String... rest) {
        return new ValueListCondition(field, true, checkedValues(first, rest));
    }

    /** {@code FieldValueNotIncludedInValueList(field, "v1", …)} — domain-checked (see {@link #in}). */
    public Condition notIn(String first, String... rest) {
        return new ValueListCondition(field, false, checkedValues(first, rest));
    }

    /**
     * {@code FieldValueIncludedInValueList(field, [f1], …)} — membership against other enumeration fields' stored
     * values, the enum sibling of {@link StringRef#in(StringRef, StringRef...)}. An empty member field never
     * matches. catalog:FieldValueIncludedInValueList
     */
    public Condition in(EnumRef first, EnumRef... rest) {
        return new ValueListCondition(field, true, fieldValues(first, rest));
    }

    /**
     * {@code FieldValueNotIncludedInValueList(field, [f1], …)} — field-reference entries (see
     * {@link #in(EnumRef, EnumRef...)}). An empty member makes a firing an omission, since filling it could
     * clear the error. catalog:FieldValueNotIncludedInValueList
     */
    public Condition notIn(EnumRef first, EnumRef... rest) {
        return new ValueListCondition(field, false, fieldValues(first, rest));
    }

    private static List<Calculation> fieldValues(EnumRef first, EnumRef... rest) {
        var values = new java.util.ArrayList<Calculation>();
        values.add(first.value());
        for (EnumRef r : rest) {
            values.add(r.value());
        }
        return values;
    }

    private List<Calculation> checkedValues(String first, String... rest) {
        var values = new java.util.ArrayList<Calculation>();
        values.add(checkedLiteral(first));
        for (String v : rest) {
            values.add(checkedLiteral(v));
        }
        return values;
    }

    EnumValueLiteral checkedLiteral(String value) {
        if (!allowedValues.contains(value)) {
            throw Refusals.of(KernelCode.MVK_INVALID_STRING_CONSTANT_FOR_ENUMERATION,
                    "'" + value + "' is not a value of " + field.path()
                            + " — allowed: " + allowedValues);
        }
        return new EnumValueLiteral(value);
    }
}
