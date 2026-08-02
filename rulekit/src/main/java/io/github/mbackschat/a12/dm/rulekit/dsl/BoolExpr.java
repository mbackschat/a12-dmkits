package io.github.mbackschat.a12.dm.rulekit.dsl;

import io.github.mbackschat.a12.dm.core.ValueKind;

/** A computed yes/no value, currently produced by {@link Dsl#firstFilledBoolean}. */
public record BoolExpr(Calculation expression) {

    public BoolExpr {
        if (expression.kind() != ValueKind.BOOLEAN) {
            throw new IllegalArgumentException("BoolExpr needs a BOOLEAN expression, got " + expression.kind());
        }
    }

    public Condition isTrue() {
        return new ComparisonCondition(expression, CompareOp.EQ, new BoolLiteral(true));
    }

    public Condition isFalse() {
        return new ComparisonCondition(expression, CompareOp.EQ, new BoolLiteral(false));
    }

    public Condition isNotTrue() {
        return new ComparisonCondition(expression, CompareOp.NE, new BoolLiteral(true));
    }

    public Condition isNotFalse() {
        return new ComparisonCondition(expression, CompareOp.NE, new BoolLiteral(false));
    }
}
