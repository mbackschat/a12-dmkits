package io.github.mbackschat.a12.dm.rulekit.dsl;

/**
 * An enum-valued expression that can be compared by stored value — a {@link Dsl#firstFilledEnum first
 * specified value} over an enum repetition list, or a {@link EnumRef#forIndex semantic index} on an enum
 * field. The enum peer of {@link StringExpr}; it compares by stored value against a literal, an
 * {@link EnumRef enum field}, or another {@link EnumExpr}. Type-compatibility of the two enums is the
 * kernel's call (F4/N7), as for {@link EnumRef#is(EnumRef)}.
 */
public record EnumExpr(Calculation expression) {

    /**
     * {@code <this> + <text>} — this enumeration expression joined to text, contributing its stored value (KERNEL-FINDINGS
     * KF530). A key-selected cell is read braced, which A12 admits whatever its labels (KF396, KF655).
     * catalog:Concatenation
     */
    public StringExpr plus(StringExpr text) {
        return StringExpr.joined(expression, text.expression());
    }

    /** {@code <this> + "literal"} — as {@link #plus(StringExpr)}, followed by a string literal. */
    public StringExpr plus(String literal) {
        return plus(new StringExpr(new StringLiteral(literal)));
    }

    /** {@code <this> + [text]} — as {@link #plus(StringExpr)}, followed by a text field's value. */
    public StringExpr plus(StringRef text) {
        return plus(text.asString());
    }

    /** {@code <this> == "value"} — compared by stored value (the enum domain is the kernel's check). */
    public Condition is(String value) {
        return new ComparisonCondition(expression, CompareOp.EQ, new EnumValueLiteral(value));
    }

    public Condition isNot(String value) {
        return new ComparisonCondition(expression, CompareOp.NE, new EnumValueLiteral(value));
    }

    /** {@code <this> == [other]} — compared to an enum field by stored value. */
    public Condition is(EnumRef other) {
        return new ComparisonCondition(expression, CompareOp.EQ, other.value());
    }

    public Condition isNot(EnumRef other) {
        return new ComparisonCondition(expression, CompareOp.NE, other.value());
    }

    /** {@code <this> == <other>} — compared to another enum expression by stored value. */
    public Condition is(EnumExpr other) {
        return new ComparisonCondition(expression, CompareOp.EQ, other.expression());
    }

    public Condition isNot(EnumExpr other) {
        return new ComparisonCondition(expression, CompareOp.NE, other.expression());
    }

    // --- eq/ne: the equality verbs shared with the value refs (D1); is/isNot above stay as the
    //     domain-natural enum reading.

    public Condition eq(String value) {
        return is(value);
    }

    public Condition ne(String value) {
        return isNot(value);
    }

    public Condition eq(EnumRef other) {
        return is(other);
    }

    public Condition ne(EnumRef other) {
        return isNot(other);
    }

    public Condition eq(EnumExpr other) {
        return is(other);
    }

    public Condition ne(EnumExpr other) {
        return isNot(other);
    }
}
