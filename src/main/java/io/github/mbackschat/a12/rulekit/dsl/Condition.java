package io.github.mbackschat.a12.rulekit.dsl;

/**
 * A boolean expression — the kind that renders into a rule's {@code errorCondition} and into
 * computation pre-/common-preconditions (proposal §5). Immutable and composable: {@link #and} /
 * {@link #or} build new nodes (N5). The closed set of node types lets the renderer dispatch with an
 * exhaustive switch.
 */
public sealed interface Condition
        permits FieldPresenceCondition, FieldListPredicate, ComparisonCondition, LogicCondition {

    default Condition and(Condition other) {
        return new LogicCondition(LogicCondition.Op.AND, this, other);
    }

    default Condition or(Condition other) {
        return new LogicCondition(LogicCondition.Op.OR, this, other);
    }
}
