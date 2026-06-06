package io.github.mbackschat.a12.dm.rulekit.dsl;

import java.util.List;

/**
 * A typed handle to one category of an enumeration field — obtained via
 * {@link EnumRef#category(String)} (which fails fast on an unknown category,
 * {@code MVK_CATEGORY_UNKOWN}). Comparisons are validated against the <em>category's</em> value
 * domain — comparing to a plain enum value is the kernel-rejected mistake
 * ({@code MVK_INVALID_STRING_CONSTANT_FOR_ENUM_COMPARISON}, verified) this handle erases.
 */
public record CategoryRef(FieldRef field, String name, List<String> values) {

    public CategoryRef {
        values = List.copyOf(values);
    }

    /** {@code [field -> name] == value}. */
    public Condition is(String value) {
        return compareTo(CompareOp.EQ, value);
    }

    /** {@code [field -> name] != value}. */
    public Condition isNot(String value) {
        return compareTo(CompareOp.NE, value);
    }

    /** {@code [field -> name] == [other -> name]} — category-vs-category (the kernel arbitrates compatibility). */
    public Condition is(CategoryRef other) {
        return new ComparisonCondition(new EnumCategoryAccess(field, name), CompareOp.EQ,
                new EnumCategoryAccess(other.field, other.name));
    }

    public Condition isNot(CategoryRef other) {
        return new ComparisonCondition(new EnumCategoryAccess(field, name), CompareOp.NE,
                new EnumCategoryAccess(other.field, other.name));
    }

    private Condition compareTo(CompareOp op, String value) {
        if (!values.contains(value)) {
            throw new IllegalArgumentException("'" + value + "' is not a value of category '" + name
                    + "' on " + field.path() + " — allowed: " + values
                    + " (comparing to a plain enum value is MVK_INVALID_STRING_CONSTANT_FOR_ENUM_COMPARISON)");
        }
        return new ComparisonCondition(new EnumCategoryAccess(field, name), op, new EnumValueLiteral(value));
    }
}
