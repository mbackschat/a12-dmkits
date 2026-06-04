package io.github.mbackschat.a12.rulekit.dsl;

/**
 * A date-valued operand — a date {@link DateRef field}, {@link Dsl#today() Today}, or an
 * {@link Dsl#addMonths add} result ({@link DateExpr}). Dates are orderable (§5b), and unlike numbers
 * they carry no scale, so field-vs-field date comparison needs none of the D13 scale machinery.
 * The comparison builders are default methods so every date operand compares the same way.
 */
public sealed interface DateOperand permits DateRef, DateExpr {

    /** This operand as a value expression (a date {@link FieldValue}, {@link Today}, or {@link DateShift}). */
    Calculation expression();

    default Condition before(DateOperand other) {
        return new ComparisonCondition(expression(), CompareOp.LT, other.expression());
    }

    default Condition onOrBefore(DateOperand other) {
        return new ComparisonCondition(expression(), CompareOp.LE, other.expression());
    }

    default Condition after(DateOperand other) {
        return new ComparisonCondition(expression(), CompareOp.GT, other.expression());
    }

    default Condition onOrAfter(DateOperand other) {
        return new ComparisonCondition(expression(), CompareOp.GE, other.expression());
    }

    default Condition eq(DateOperand other) {
        return new ComparisonCondition(expression(), CompareOp.EQ, other.expression());
    }

    default Condition ne(DateOperand other) {
        return new ComparisonCondition(expression(), CompareOp.NE, other.expression());
    }
}
