package io.github.mbackschat.a12.dm.rulekit.dsl;

import java.util.ArrayList;
import java.util.List;

/**
 * A string-valued expression that can be concatenated and compared — a field value, a string literal,
 * or a {@code +}-concatenation of them. The string peer of {@link NumberExpr}: {@link #plus} builds a
 * flat {@link Concatenation}, and {@link StringRef} delegates its expression comparisons here.
 */
public record StringExpr(Calculation expression) {

    /** {@code FieldFilled([Field For key])} — the presence of this indexed cell; valid only on a {@code forIndex(...)} result. */
    public Condition filled() {
        return IndexedPresenceCondition.of(expression, true);
    }

    /** {@code FieldNotFilled([Field For key])} — the absence of this indexed cell; valid only on a {@code forIndex(...)} result. */
    public Condition notFilled() {
        return IndexedPresenceCondition.of(expression, false);
    }

    /** {@code this + other} — string concatenation, flattened so {@code a + b + c} renders as one chain. */
    public StringExpr plus(StringExpr other) {
        List<Calculation> parts = new ArrayList<>();
        addParts(parts, this.expression);
        addParts(parts, other.expression);
        return new StringExpr(new Concatenation(parts));
    }

    /** {@code this + "literal"} — append a string literal. */
    public StringExpr plus(String literal) {
        return plus(new StringExpr(new StringLiteral(literal)));
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
