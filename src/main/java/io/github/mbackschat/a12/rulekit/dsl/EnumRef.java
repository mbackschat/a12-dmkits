package io.github.mbackschat.a12.rulekit.dsl;

import java.util.List;
import java.util.Map;

/**
 * A typed handle to an enumeration field. Comparisons are by <em>stored value</em> (§5b), and the
 * value is checked against the model's enum domain at build time — {@code is("NOPE")} on a field
 * whose values are {@code BASIC/PRO/ENTERPRISE} fails fast rather than producing text the kernel
 * later rejects. {@link #category(String)} navigates to a model-declared category
 * ({@code [field -> Category]}). Obtain from {@link BoundModel#enumOf(String)}.
 */
public record EnumRef(FieldRef field, List<String> allowedValues, Map<String, List<String>> categories) {

    public EnumRef {
        allowedValues = List.copyOf(allowedValues);
        categories = Map.copyOf(categories);
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

    private Condition compareTo(CompareOp op, String value) {
        if (!allowedValues.contains(value)) {
            throw new IllegalArgumentException(
                    "'" + value + "' is not a value of " + field.path() + " — allowed: " + allowedValues
                            + io.github.mbackschat.a12.rulekit.model.Suggest.hint(value, allowedValues));
        }
        return new ComparisonCondition(field.value(), op, new EnumValueLiteral(value));
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
