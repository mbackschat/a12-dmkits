package io.github.mbackschat.a12.rulekit.dsl;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The catalog operator-ids a {@link Condition} (and its nested {@link Calculation}s) applies —
 * collected by exhaustive switch over the sealed AST, the sibling of {@link ConditionReferences}.
 * It powers the F4a gotcha/INFO channel (surface each operator's catalog {@code gotchas}) and is the
 * seed of F9's "operators used" projection. Value-only nodes (field values, number/string/enum
 * literals) apply no operator and contribute nothing; {@code Having}/{@code FromScope} are
 * sub-constructs with their own catalog entries, so they are surfaced where they occur.
 */
public final class OperatorUsage {

    private OperatorUsage() {
    }

    /** The distinct operator-ids used by {@code condition}, in encounter order. */
    public static Set<String> of(Condition condition) {
        Set<String> ops = new LinkedHashSet<>();
        collect(condition, ops);
        return java.util.Collections.unmodifiableSet(ops);
    }

    /** The distinct operator-ids used by a value expression (e.g. a computation's operation). */
    public static Set<String> of(Calculation calculation) {
        Set<String> ops = new LinkedHashSet<>();
        collect(calculation, ops);
        return java.util.Collections.unmodifiableSet(ops);
    }

    private static void collect(Condition condition, Set<String> ops) {
        ops.add(condition.operatorId());
        switch (condition) {
            case FieldPresenceCondition ignored -> { }
            case FieldListPredicate p -> p.fields().forEach(f -> collectOperand(f, ops));
            case ComparisonCondition c -> {
                collect(c.left(), ops);
                collect(c.right(), ops);
            }
            case LogicCondition l -> {
                collect(l.left(), ops);
                collect(l.right(), ops);
            }
            case GroupPresenceCondition ignored -> { }
            case GroupListPredicate ignored -> { }
            case PatternCondition ignored -> { }
            case ValueListCondition v -> v.values().forEach(value -> collect(value, ops));
            case FieldListValueListCondition v -> {
                v.fields().forEach(f -> collectOperand(f, ops));
                v.values().forEach(value -> collect(value, ops));
            }
            case RepetitionNotUniqueCondition r -> r.scope().ifPresent(s -> ops.add("FromScope"));
            case CustomConditionRef ignored -> { }
        }
    }

    private static void collect(Calculation calculation, Set<String> ops) {
        switch (calculation) {
            case FieldValue ignored -> { }
            case NumberLiteral ignored -> { }
            case StringLiteral ignored -> { }
            case EnumValueLiteral ignored -> { }
            case BoolLiteral b -> ops.add(b.operatorId());
            case LengthOf l -> ops.add(l.operatorId());
            case EnumCategoryAccess c -> ops.add(c.operatorId());
            case CountAggregate a -> {
                ops.add(a.operatorId());
                a.fields().forEach(f -> collectOperand(f, ops));
            }
            case ListAggregate a -> {
                ops.add(a.operatorId());
                a.operands().forEach(o -> collectOperand(o, ops));
            }
            case CurrentRepetition r -> ops.add(r.operatorId());
            case Today t -> ops.add(t.operatorId());
            case DateDifference d -> {
                ops.add(d.operatorId());
                collect(d.left(), ops);
                collect(d.right(), ops);
            }
            case DateShift s -> {
                ops.add(s.operatorId());
                collect(s.date(), ops);
            }
            case SemanticIndexValue v -> ops.add(v.operatorId());
        }
    }

    /** A list operand may carry a {@code Having} filter — a sub-construct with its own catalog entry. */
    private static void collectOperand(FieldOperand operand, Set<String> ops) {
        if (operand instanceof FieldListRef list) {
            list.filter().ifPresent(filter -> {
                ops.add("Having");
                collect(filter, ops);
            });
        }
    }
}
