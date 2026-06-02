package io.github.mbackschat.a12.rulekit.dsl;

/**
 * A typed handle to a number field. Only number fields get the comparison builders, so a
 * type-illegal ordering can't be written (§5b). The declared {@code scale} (decimal places) is read
 * from the model and will feed the scale pre-check (D13) once comparisons take field operands.
 */
public record NumberRef(FieldRef field, int scale) {

    public Condition filled() {
        return field.filled();
    }

    public Condition notFilled() {
        return field.notFilled();
    }

    public FieldValue value() {
        return field.value();
    }

    /** This field's value as a comparable numeric expression. */
    public NumberExpr asNumber() {
        return new NumberExpr(value());
    }

    public Condition le(long literal) {
        return compareTo(CompareOp.LE, literal);
    }

    public Condition lt(long literal) {
        return compareTo(CompareOp.LT, literal);
    }

    public Condition ge(long literal) {
        return compareTo(CompareOp.GE, literal);
    }

    public Condition gt(long literal) {
        return compareTo(CompareOp.GT, literal);
    }

    public Condition eq(long literal) {
        return compareTo(CompareOp.EQ, literal);
    }

    public Condition ne(long literal) {
        return compareTo(CompareOp.NE, literal);
    }

    private Condition compareTo(CompareOp op, long literal) {
        return new ComparisonCondition(value(), op, NumberLiteral.of(literal));
    }
    // Comparison construction is shared with NumberExpr (see asNumber()); kept inline here so the
    // common num(...).le(0) form needs no extra hop.
}
