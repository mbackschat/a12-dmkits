package io.github.mbackschat.a12.rulekit.dsl;

/** A binary comparison such as {@code [Quantity] <= 0}. */
public record ComparisonCondition(Calculation left, CompareOp operator, Calculation right) implements Condition {

    @Override
    public String operatorId() {
        return operator.operatorId();
    }
}
