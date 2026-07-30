package io.github.mbackschat.a12.dm.rulekit.dsl;

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
            case RuleGroupPresenceCondition ignored -> { /* operatorId (GroupFilled/GroupNotFilled) already added */ }
            case IndexedPresenceCondition p -> ops.add(p.index().operatorId());   // the SemanticIndex operator
            case GroupListPredicate ignored -> { }
            case PatternCondition ignored -> { }
            case ValueListCondition v -> v.values().forEach(value -> collect(value, ops));
            case FieldListValueListCondition v -> {
                v.fields().forEach(f -> collectOperand(f, ops));
                v.values().forEach(value -> collect(value, ops));
            }
            case FieldListEntityListCondition v -> {
                v.fields().forEach(f -> collectOperand(f, ops));
                v.valueFields().forEach(f -> collectOperand(f, ops));
            }
            case GroupScopedFieldFill ignored -> { /* operatorId already added; group scopes add no ops */ }
            case RepetitionNotUniqueCondition r -> r.scope().ifPresent(s -> ops.add("FromScope"));
            case CustomConditionRef ignored -> { }
            case DateValidity v -> collect(v.dateConstruct(), ops);
            case CustomTypeValidity ignored -> { /* operatorId already added; the field adds no operators */ }
        }
    }

    private static void collect(Calculation calculation, Set<String> ops) {
        switch (calculation) {
            case FieldValue ignored -> { }
            case NumberLiteral ignored -> { }
            case StringLiteral ignored -> { }
            case DateLiteral ignored -> { }
            case TimeLiteral ignored -> { }
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
            case Now n -> ops.add(n.operatorId());
            case BaseYear b -> ops.add(b.operatorId());
            case DateDifference d -> {
                ops.add(d.operatorId());
                collect(d.left(), ops);
                collect(d.right(), ops);
            }
            case DateShift s -> {
                ops.add(s.operatorId());
                collect(s.date(), ops);
                collect(s.amount(), ops);
            }
            case SemanticIndexValue v -> ops.add(v.operatorId());
            case SemanticIndexFieldValue v -> ops.add(v.operatorId());
            case Arithmetic a -> {
                collect(a.left(), ops);
                collect(a.right(), ops);
            }
            case Rounding r -> {
                ops.add(r.operatorId());
                collect(r.operand(), ops);
            }
            // concatenation's + is intrinsic (no catalog keyword) — just recurse into the parts
            case Concatenation c -> c.parts().forEach(p -> collect(p, ops));
            case DateListAggregate a -> {
                ops.add(a.operatorId());
                collectOperand(a.scope(), ops);
            }
            case FirstFilledValue a -> {
                ops.add(a.operatorId());
                a.operands().forEach(o -> collectOperand(o, ops));
            }
            case FieldValueAsString c -> ops.add(c.operatorId());
            case IndexedFieldValueAsString c -> {
                ops.add(c.operatorId());     // FieldValueAsString
                collect(c.index(), ops);     // + the SemanticIndex operand
            }
            case RangeAsString c -> ops.add(c.operatorId());
            case FieldValueAsNumber c -> ops.add(c.operatorId());
            case RangeAsNumber c -> ops.add(c.operatorId());
            case OpListMath m -> {
                ops.add(m.operatorId());
                m.operands().forEach(o -> collect(o, ops));
            }
            case NumberOfValueInFields n -> {
                ops.add(n.operatorId());
                collect(n.value(), ops);
                n.fields().forEach(f -> collectOperand(f, ops));
            }
            case ValueAsDate v -> {
                ops.add(v.operatorId());
                ops.add(v.dayKeywordId());
            }
            case DateExtraction e -> {
                ops.add(e.operatorId());
                collect(e.source(), ops);
            }
            case DateTimePart e -> {
                ops.add(e.operatorId());
                collect(e.source(), ops);
            }
            case SumOfProducts s -> {
                ops.add(s.operatorId());
                collectOperand(s.left(), ops);
                collectOperand(s.right(), ops);
            }
            case DateConstruction dc -> {
                ops.add(dc.operatorId());
                collect(dc.day(), ops);
                collect(dc.month(), ops);
                collect(dc.year1(), ops);
                dc.year2().ifPresent(y -> collect(y, ops));
            }
            case TimeConstruction tc -> {
                ops.add(tc.operatorId());
                tc.components().forEach(component -> collect(component, ops));
            }
            case DateTimeConstruction dtc -> {
                ops.add(dtc.operatorId());
                collect(dtc.date(), ops);
                collect(dtc.time(), ops);
            }
            case FieldRounding r -> ops.add(r.operatorId());
            case DateRangeBound b -> {
                ops.add(b.operatorId());
                collect(b.source(), ops);
            }
            case RootGroupCount c -> ops.add(c.operatorId());
            case DateRangeConstruction dr -> {
                ops.add(dr.operatorId());
                collect(dr.start(), ops);
                collect(dr.end(), ops);
            }
        }
    }

    /** A list operand may be a category projection (its own catalog operator) or carry a {@code Having}
     *  filter — either is a sub-construct with a catalog entry. Accepts the wider {@link ValueListOperand}. */
    private static void collectOperand(ValueListOperand operand, Set<String> ops) {
        if (operand instanceof EnumCategoryAccess c) {
            ops.add(c.operatorId());   // the `->` category projection is its own catalog operator
        } else if (operand instanceof FieldListRef list) {
            list.filter().ifPresent(filter -> {
                ops.add("Having");
                collect(filter, ops);
            });
        }
    }
}
