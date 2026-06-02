package io.github.mbackschat.a12.rulekit.dsl;

/**
 * A numeric-valued expression that can be compared — a field value, a {@code Length(...)}, an
 * aggregate, an arithmetic result, … Centralizes the comparison builders so every numeric operand
 * compares the same way. (Scale-mismatch on {@code ==}/{@code !=} is left to the kernel — D13.)
 */
public record NumberExpr(Calculation expression) {

    public Condition lt(long literal) {
        return compareTo(CompareOp.LT, literal);
    }

    public Condition le(long literal) {
        return compareTo(CompareOp.LE, literal);
    }

    public Condition gt(long literal) {
        return compareTo(CompareOp.GT, literal);
    }

    public Condition ge(long literal) {
        return compareTo(CompareOp.GE, literal);
    }

    public Condition eq(long literal) {
        return compareTo(CompareOp.EQ, literal);
    }

    public Condition ne(long literal) {
        return compareTo(CompareOp.NE, literal);
    }

    private Condition compareTo(CompareOp op, long literal) {
        return new ComparisonCondition(expression, op, NumberLiteral.of(literal));
    }
}
