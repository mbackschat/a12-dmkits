package io.github.mbackschat.a12.rulekit.dsl;

/**
 * {@code And} / {@code Or} of two conditions. The kernel has no negation and requires parentheses
 * when And and Or are mixed — the renderer adds them based on the operator of nested children.
 */
public record LogicCondition(Op op, Condition left, Condition right) implements Condition {

    public enum Op {
        AND,
        OR
    }
}
