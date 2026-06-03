package io.github.mbackschat.a12.rulekit.dsl;

import java.math.BigDecimal;

/**
 * A numeric-valued expression that can be compared — a field value, a {@code Length(...)}, an
 * aggregate, an arithmetic result, … Centralizes the comparison builders so every numeric operand
 * compares the same way ({@link NumberRef} delegates here).
 *
 * <p>Literal overloads are <strong>scale-faithful</strong>: {@code eq("0.00")} renders exactly
 * {@code == 0.00} (study finding 2). Note the kernel's decimal-scale check
 * ({@code MVK_INVALID_COMPARE_DEC_PLACES}) applies to <em>field-vs-field</em> comparisons only —
 * numeric literals are accepted at any written scale (verified; see
 * {@code KERNEL-INTEGRATION-NOTES.md}). Field-vs-field comparison and its cheap scale pre-check
 * (D13) are a later increment.
 */
public record NumberExpr(Calculation expression) {

    public Condition lt(long literal) {
        return cmp(CompareOp.LT, NumberLiteral.of(literal));
    }

    public Condition lt(String literal) {
        return cmp(CompareOp.LT, NumberLiteral.of(literal));
    }

    public Condition lt(BigDecimal literal) {
        return cmp(CompareOp.LT, new NumberLiteral(literal));
    }

    public Condition le(long literal) {
        return cmp(CompareOp.LE, NumberLiteral.of(literal));
    }

    public Condition le(String literal) {
        return cmp(CompareOp.LE, NumberLiteral.of(literal));
    }

    public Condition le(BigDecimal literal) {
        return cmp(CompareOp.LE, new NumberLiteral(literal));
    }

    public Condition gt(long literal) {
        return cmp(CompareOp.GT, NumberLiteral.of(literal));
    }

    public Condition gt(String literal) {
        return cmp(CompareOp.GT, NumberLiteral.of(literal));
    }

    public Condition gt(BigDecimal literal) {
        return cmp(CompareOp.GT, new NumberLiteral(literal));
    }

    public Condition ge(long literal) {
        return cmp(CompareOp.GE, NumberLiteral.of(literal));
    }

    public Condition ge(String literal) {
        return cmp(CompareOp.GE, NumberLiteral.of(literal));
    }

    public Condition ge(BigDecimal literal) {
        return cmp(CompareOp.GE, new NumberLiteral(literal));
    }

    public Condition eq(long literal) {
        return cmp(CompareOp.EQ, NumberLiteral.of(literal));
    }

    public Condition eq(String literal) {
        return cmp(CompareOp.EQ, NumberLiteral.of(literal));
    }

    public Condition eq(BigDecimal literal) {
        return cmp(CompareOp.EQ, new NumberLiteral(literal));
    }

    public Condition ne(long literal) {
        return cmp(CompareOp.NE, NumberLiteral.of(literal));
    }

    public Condition ne(String literal) {
        return cmp(CompareOp.NE, NumberLiteral.of(literal));
    }

    public Condition ne(BigDecimal literal) {
        return cmp(CompareOp.NE, new NumberLiteral(literal));
    }

    private Condition cmp(CompareOp op, NumberLiteral literal) {
        return new ComparisonCondition(expression, op, literal);
    }
}
