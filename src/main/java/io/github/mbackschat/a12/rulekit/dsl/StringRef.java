package io.github.mbackschat.a12.rulekit.dsl;

/**
 * A typed handle to a string field. Offers string equality (the kernel has no ordering on strings —
 * §5b) and {@code Length(...)} as a numeric expression. Obtain from {@link BoundModel#str(String)}.
 */
public record StringRef(FieldRef field) {

    public Condition filled() {
        return field.filled();
    }

    public Condition notFilled() {
        return field.notFilled();
    }

    public Condition eq(String literal) {
        return new ComparisonCondition(field.value(), CompareOp.EQ, new StringLiteral(literal));
    }

    public Condition ne(String literal) {
        return new ComparisonCondition(field.value(), CompareOp.NE, new StringLiteral(literal));
    }

    /** {@code Length(field)} as a numeric expression for comparison. */
    public NumberExpr length() {
        return new NumberExpr(new LengthOf(field));
    }
}
