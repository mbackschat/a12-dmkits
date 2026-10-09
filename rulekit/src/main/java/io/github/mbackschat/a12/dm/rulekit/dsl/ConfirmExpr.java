package io.github.mbackschat.a12.dm.rulekit.dsl;

import io.github.mbackschat.a12.dm.core.ValueKind;

/**
 * A confirm-valued expression — a {@link ConfirmRef#forIndex semantic index} on a confirm field. Like
 * {@link ConfirmRef} it compares only to {@code True}: {@code == False} is {@code MVK_INVALID_COMPARE_TO_YES}, so
 * there is deliberately no {@code isFalse()}.
 */
public record ConfirmExpr(Calculation expression) {

    public ConfirmExpr {
        if (expression.kind() != ValueKind.CONFIRM) {
            throw new IllegalArgumentException("ConfirmExpr needs a CONFIRM expression, got " + expression.kind());
        }
    }

    /** {@code <this> == True}. catalog:Equal */
    public Condition confirmed() {
        return new ComparisonCondition(expression, CompareOp.EQ, new BoolLiteral(true));
    }

    /** {@code <this> != True} — "not confirmed". catalog:NotEqual */
    public Condition notConfirmed() {
        return new ComparisonCondition(expression, CompareOp.NE, new BoolLiteral(true));
    }
}
