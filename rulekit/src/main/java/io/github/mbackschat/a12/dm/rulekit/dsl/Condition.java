package io.github.mbackschat.a12.dm.rulekit.dsl;

import java.util.function.UnaryOperator;

/**
 * A boolean expression — the kind that renders into a rule's {@code errorCondition} and into
 * computation pre-/common-preconditions (PROJECT-DESIGN §5). Immutable and composable: {@link #and} /
 * {@link #or} build new nodes (N5). The closed set of node types lets the renderer dispatch with an
 * exhaustive switch.
 *
 * <p><strong>D14 invariant</strong> (PROJECT-DESIGN §8/§17): every condition node is one operator
 * application and {@link #operatorId()} names its catalog entry; its field references are
 * model-resolved {@link FieldRef}s carrying a provenance {@link PathStyle} hint. That is what makes
 * explain (F9) and the text→AST converter (F10) cheap projections over this tree.
 */
public sealed interface Condition
        permits FieldPresenceCondition, EntityListPredicate, ComparisonCondition, LogicCondition,
        GroupPresenceCondition, RuleGroupPresenceCondition, IndexedPresenceCondition, GroupListPredicate,
        PatternCondition, ValueListCondition, FieldListValueListCondition, FieldListEntityListCondition,
        RepetitionNotUniqueCondition, CustomConditionRef, DateValidity, CustomTypeValidity {

    /** The catalog operator-id this node applies — the renderer's only source of keywords. */
    String operatorId();

    /** {@code a And b} — logical conjunction; {@link Dsl#allOf} folds a flat {@code And} chain. catalog:And */
    default Condition and(Condition other) {
        return new LogicCondition(LogicCondition.Op.AND, this, other);
    }

    /** {@code a Or b} — logical disjunction; {@link Dsl#anyOf} folds a flat {@code Or} chain. catalog:Or */
    default Condition or(Condition other) {
        return new LogicCondition(LogicCondition.Op.OR, this, other);
    }

    /**
     * Bottom-up rewrite — the in-session modify capability (#1, PROJECT-DESIGN §12). The rewriter is
     * applied to every <em>condition</em> node, children first (so it sees already-rewritten
     * children); returning the node unchanged keeps it. Untouched subtrees are reused by reference
     * (N5), so a no-op transform returns {@code this}.
     *
     * <p>Calculations are atomic operands here: today no {@link Calculation} nests another, so a
     * calculation rewriter would be pure ceremony — it lands with the first nesting calculation
     * node (arithmetic/aggregates, corpus backlog items 6–7).
     */
    default Condition transform(UnaryOperator<Condition> rewriter) {
        Condition rebuilt = switch (this) {
            case LogicCondition l -> {
                Condition left = l.left().transform(rewriter);
                Condition right = l.right().transform(rewriter);
                yield (left == l.left() && right == l.right()) ? l : new LogicCondition(l.op(), left, right);
            }
            case FieldPresenceCondition f -> f;
            case EntityListPredicate p -> p;
            case ComparisonCondition c -> c;
            case GroupPresenceCondition g -> g;
            case RuleGroupPresenceCondition r -> r;
            case IndexedPresenceCondition p -> p;
            case GroupListPredicate p -> p;
            case PatternCondition p -> p;
            case ValueListCondition v -> v;
            case FieldListValueListCondition v -> v;
            case FieldListEntityListCondition v -> v;
            case RepetitionNotUniqueCondition r -> r;
            case CustomConditionRef c -> c;
            case DateValidity v -> v;
            case CustomTypeValidity v -> v;
        };
        return rewriter.apply(rebuilt);
    }
}
