package io.github.mbackschat.a12.dm.rulekit.dsl;

/**
 * A time-of-day operand — a {@link TimeRef time field} or a {@link TimeExpr} (e.g. the
 * {@link Dsl#timeFromDateTime} of a date-time). Times are orderable (§5b) and, like dates, carry no
 * scale, so time-vs-time comparison needs none of the D13 number-scale machinery. The comparison
 * builders are default methods so every time operand compares the same way.
 */
public sealed interface TimeOperand extends TemporalOperand permits TimeRef, TimeExpr {

    /** This operand as a value expression (a time {@link FieldValue} or a {@link Dsl#timeFromDateTime}). */
    @Override
    Calculation expression();

    default Condition before(TimeOperand other) {
        return new ComparisonCondition(expression(), CompareOp.LT, other.expression());
    }

    default Condition onOrBefore(TimeOperand other) {
        return new ComparisonCondition(expression(), CompareOp.LE, other.expression());
    }

    default Condition after(TimeOperand other) {
        return new ComparisonCondition(expression(), CompareOp.GT, other.expression());
    }

    default Condition onOrAfter(TimeOperand other) {
        return new ComparisonCondition(expression(), CompareOp.GE, other.expression());
    }

    default Condition eq(TimeOperand other) {
        return new ComparisonCondition(expression(), CompareOp.EQ, other.expression());
    }

    default Condition ne(TimeOperand other) {
        return new ComparisonCondition(expression(), CompareOp.NE, other.expression());
    }
}
