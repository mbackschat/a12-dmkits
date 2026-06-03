package io.github.mbackschat.a12.rulekit.dsl;

/**
 * A boolean expression — the kind that renders into a rule's {@code errorCondition} and into
 * computation pre-/common-preconditions (proposal §5). Immutable and composable: {@link #and} /
 * {@link #or} build new nodes (N5). The closed set of node types lets the renderer dispatch with an
 * exhaustive switch.
 *
 * <p><strong>D14 invariant</strong> (proposal §8/§17): every condition node is one operator
 * application and {@link #operatorId()} names its catalog entry; its field references are
 * model-resolved {@link FieldRef}s carrying a provenance {@link PathStyle} hint. That is what makes
 * explain (F9) and the text→AST converter (F10) cheap projections over this tree.
 */
public sealed interface Condition
        permits FieldPresenceCondition, FieldListPredicate, ComparisonCondition, LogicCondition {

    /** The catalog operator-id this node applies — the renderer's only source of keywords. */
    String operatorId();

    default Condition and(Condition other) {
        return new LogicCondition(LogicCondition.Op.AND, this, other);
    }

    default Condition or(Condition other) {
        return new LogicCondition(LogicCondition.Op.OR, this, other);
    }
}
