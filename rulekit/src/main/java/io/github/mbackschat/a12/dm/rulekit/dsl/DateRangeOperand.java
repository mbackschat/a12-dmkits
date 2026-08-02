package io.github.mbackschat.a12.dm.rulekit.dsl;

/**
 * A date-range value: a {@link DateRangeRef field}, a
 * {@link DateRangeConstruction DateRange(start, end)} construction, or a typed computed
 * {@link DateRangeExpr}. The kernel admits equality and inequality for this kind; ordering comparisons
 * are not part of the domain.
 */
public sealed interface DateRangeOperand permits DateRangeRef, DateRangeConstruction, DateRangeExpr {

    Calculation expression();

    default Condition eq(DateRangeOperand other) {
        return new ComparisonCondition(expression(), CompareOp.EQ, other.expression());
    }

    default Condition ne(DateRangeOperand other) {
        return new ComparisonCondition(expression(), CompareOp.NE, other.expression());
    }
}
