package io.github.mbackschat.a12.dm.rulekit.dsl;

import java.util.List;
import java.util.Map;

/**
 * A typed handle to an enumeration field. Comparisons are by <em>stored value</em> (§5b), and the
 * value is checked against the model's enum domain at build time — {@code is("NOPE")} on a field
 * whose values are {@code BASIC/PRO/ENTERPRISE} fails fast rather than producing text the kernel
 * later rejects. {@link #category(String)} navigates to a model-declared category
 * ({@code [field -> Category]}). Obtain from {@link BoundModel#enumOf(String)}.
 */
public record EnumRef(FieldRef field, List<String> allowedValues, Map<String, List<String>> categories,
        boolean correlated) {

    public EnumRef {
        allowedValues = List.copyOf(allowedValues);
        categories = Map.copyOf(categories);
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

    private FieldValue value() {
        return new FieldValue(field, correlated);
    }

    /** The category named {@code name} — fails fast if the model declares no such category. */
    public CategoryRef category(String name) {
        List<String> values = categories.get(name);
        if (values == null) {
            throw new IllegalArgumentException("'" + name + "' is not a category of " + field.path()
                    + " — declared: " + categories.keySet() + " (MVK_CATEGORY_UNKOWN)");
        }
        return new CategoryRef(field, name, values);
    }

    public Condition filled() {
        return field.filled();
    }

    public Condition notFilled() {
        return field.notFilled();
    }

    public Condition is(String value) {
        return compareTo(CompareOp.EQ, value);
    }

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

    private Condition compareTo(CompareOp op, String value) {
        if (!allowedValues.contains(value)) {
            throw new IllegalArgumentException(
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
            throw new IllegalArgumentException("'" + value + "' is not a value of " + field.path()
                    + " — allowed: " + allowedValues + " (MVK_INVALID_STRING_CONSTANT_FOR_ENUMERATION)");
        }
        return new EnumValueLiteral(value);
    }
}
