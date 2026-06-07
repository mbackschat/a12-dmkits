package io.github.mbackschat.a12.dm.rulekit.dsl;

/**
 * A date-time-valued operand — a {@link DateTimeRef date-time field} or a {@link DateTimeExpr} (a
 * {@code DateTime(date, time)} construction or a sub-day shift). Date-times are orderable like dates
 * and times (§5b) and carry no scale, so comparison needs none of the D13 machinery. A date-time
 * field compares to another date-time value (a field or a {@code DateTime(...)} construction), not to
 * a bare string literal (the kernel rejects that — build the value with {@code DateTime(...)}).
 */
public sealed interface DateTimeOperand extends TemporalOperand permits DateTimeRef, DateTimeExpr {

    /** This operand as a value expression (a date-time {@link FieldValue} or a {@link DateTimeConstruction}). */
    @Override
    Calculation expression();

    default Condition before(DateTimeOperand other) {
        return new ComparisonCondition(expression(), CompareOp.LT, other.expression());
    }

    default Condition onOrBefore(DateTimeOperand other) {
        return new ComparisonCondition(expression(), CompareOp.LE, other.expression());
    }

    default Condition after(DateTimeOperand other) {
        return new ComparisonCondition(expression(), CompareOp.GT, other.expression());
    }

    default Condition onOrAfter(DateTimeOperand other) {
        return new ComparisonCondition(expression(), CompareOp.GE, other.expression());
    }

    default Condition eq(DateTimeOperand other) {
        return new ComparisonCondition(expression(), CompareOp.EQ, other.expression());
    }

    default Condition ne(DateTimeOperand other) {
        return new ComparisonCondition(expression(), CompareOp.NE, other.expression());
    }
}
