package io.github.mbackschat.a12.dm.rulekit.dsl;

/** A computed date-range value, currently produced by {@link Dsl#firstFilledDateRange}. */
public final class DateRangeExpr implements DateRangeOperand {

    private final Calculation expression;

    DateRangeExpr(Calculation expression) {
        if (expression.kind() != io.github.mbackschat.a12.dm.core.ValueKind.DATE_RANGE) {
            throw new IllegalArgumentException("DateRangeExpr needs a DATE_RANGE expression, got "
                    + expression.kind());
        }
        this.expression = expression;
    }

    @Override
    public Calculation expression() {
        return expression;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof DateRangeExpr that && expression.equals(that.expression);
    }

    @Override
    public int hashCode() {
        return expression.hashCode();
    }

    @Override
    public String toString() {
        return "DateRangeExpr[expression=" + expression + "]";
    }
}
