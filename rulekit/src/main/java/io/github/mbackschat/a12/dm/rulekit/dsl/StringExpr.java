package io.github.mbackschat.a12.dm.rulekit.dsl;

import java.util.ArrayList;
import java.util.List;

/**
 * A string-valued expression that can be concatenated and compared — a field value, a string literal,
 * or a {@code +}-concatenation of them. The string peer of {@link NumberExpr}: {@link #plus} builds a
 * flat {@link Concatenation}, and {@link StringRef} delegates its expression comparisons here.
 */
public record StringExpr(Calculation expression) {

    /** {@code this + other} — string concatenation, flattened so {@code a + b + c} renders as one chain. catalog:Concatenation */
    public StringExpr plus(StringExpr other) {
        return joined(expression, other.expression);
    }

    /** {@code this + "literal"} — append a string literal. */
    public StringExpr plus(String literal) {
        return plus(new StringExpr(new StringLiteral(literal)));
    }

    /** {@code this + { [enumeration] }} — append an enumeration's value; see {@link EnumRef#plus(StringExpr)}. */
    public StringExpr plus(EnumRef enumeration) {
        return joined(expression, enumeration.field().value());
    }

    /** {@code this + [enumeration -> category]} — append a category projection's value; see {@link CategoryRef#plus}. */
    public StringExpr plus(CategoryRef category) {
        return joined(expression, category.value());
    }

    /** {@code left + right} as one flat chain, either side possibly a concatenation already. */
    static StringExpr joined(Calculation left, Calculation right) {
        List<Calculation> parts = new ArrayList<>();
        addParts(parts, left);
        addParts(parts, right);
        return new StringExpr(new Concatenation(parts));
    }

    private static void addParts(List<Calculation> acc, Calculation c) {
        if (c instanceof Concatenation concat) {
            acc.addAll(concat.parts());
        } else {
            acc.add(c);
        }
    }

    /** {@code this == "literal"} — used when a string expression (e.g. a coercion) is the left operand. */
    public Condition eq(String literal) {
        return new ComparisonCondition(expression, CompareOp.EQ, new StringLiteral(literal));
    }

    /** {@code this != "literal"}. */
    public Condition ne(String literal) {
        return new ComparisonCondition(expression, CompareOp.NE, new StringLiteral(literal));
    }

    /** {@code this == <other string expression>}. */
    public Condition eq(StringExpr other) {
        return new ComparisonCondition(expression, CompareOp.EQ, other.expression);
    }

    /** {@code this != <other string expression>}. */
    public Condition ne(StringExpr other) {
        return new ComparisonCondition(expression, CompareOp.NE, other.expression);
    }
}
