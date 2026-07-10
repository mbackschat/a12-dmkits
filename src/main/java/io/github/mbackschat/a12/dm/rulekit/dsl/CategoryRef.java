package io.github.mbackschat.a12.dm.rulekit.dsl;

import java.util.List;

/**
 * A typed handle to one category of an enumeration field — obtained via
 * {@link EnumRef#category(String)} (which fails fast on an unknown category,
 * {@code MVK_CATEGORY_UNKOWN}). Comparisons are validated against the <em>category's</em> value
 * domain — comparing to a plain enum value is the kernel-rejected mistake
 * ({@code MVK_INVALID_STRING_CONSTANT_FOR_ENUM_COMPARISON}, verified) this handle erases.
 */
public record CategoryRef(FieldRef field, String name, List<String> values, boolean correlated) {

    public CategoryRef {
        values = List.copyOf(values);
    }

    public CategoryRef(FieldRef field, String name, List<String> values) {
        this(field, name, values, false);
    }

    /**
     * Marks this reference as the {@code $} outer-iteration correlation ({@code [$field -> name]}) — it
     * binds to the enclosing rule iteration. Legal only inside a {@code Having} filter (D13).
     */
    public CategoryRef outer() {
        return new CategoryRef(field, name, values, true);
    }

    private EnumCategoryAccess access() {
        return new EnumCategoryAccess(field, name, correlated);
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
        return new ComparisonCondition(access(), CompareOp.EQ, other.access());
    }

    public Condition isNot(CategoryRef other) {
        return new ComparisonCondition(access(), CompareOp.NE, other.access());
    }

    // --- eq/ne: the equality verbs shared with the value refs (D1); is/isNot above stay as the
    //     domain-natural category reading.

    /** {@code [field -> name] == value} — the eq peer of {@link #is(String)}. */
    public Condition eq(String value) {
        return is(value);
    }

    /** {@code [field -> name] != value} — the ne peer of {@link #isNot(String)}. */
    public Condition ne(String value) {
        return isNot(value);
    }

    public Condition eq(CategoryRef other) {
        return is(other);
    }

    public Condition ne(CategoryRef other) {
        return isNot(other);
    }

    private Condition compareTo(CompareOp op, String value) {
        if (!values.contains(value)) {
            throw new IllegalArgumentException("'" + value + "' is not a value of category '" + name
                    + "' on " + field.path() + " — allowed: " + values
                    + " (comparing to a plain enum value is MVK_INVALID_STRING_CONSTANT_FOR_ENUM_COMPARISON)");
        }
        return new ComparisonCondition(access(), op, new EnumValueLiteral(value));
    }
}
