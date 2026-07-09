package io.github.mbackschat.a12.dm.rulekit.context;

import java.util.ArrayList;
import java.util.List;

import io.github.mbackschat.a12.dm.rulekit.dsl.BaseYear;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoolLiteral;
import io.github.mbackschat.a12.dm.rulekit.dsl.Now;
import io.github.mbackschat.a12.dm.rulekit.dsl.Calculation;
import io.github.mbackschat.a12.dm.rulekit.dsl.ComparisonCondition;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.CountAggregate;
import io.github.mbackschat.a12.dm.rulekit.dsl.CurrentRepetition;
import io.github.mbackschat.a12.dm.rulekit.dsl.CustomConditionRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.DateDifference;
import io.github.mbackschat.a12.dm.rulekit.dsl.DateShift;
import io.github.mbackschat.a12.dm.rulekit.dsl.DateConstruction;
import io.github.mbackschat.a12.dm.rulekit.dsl.DateRangeConstruction;
import io.github.mbackschat.a12.dm.rulekit.dsl.DateRangeBound;
import io.github.mbackschat.a12.dm.rulekit.dsl.DateTimePart;
import io.github.mbackschat.a12.dm.rulekit.dsl.CustomTypeValidity;
import io.github.mbackschat.a12.dm.rulekit.dsl.DateValidity;
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldRounding;
import io.github.mbackschat.a12.dm.rulekit.dsl.SumOfProducts;
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer;
import io.github.mbackschat.a12.dm.rulekit.dsl.EnumCategoryAccess;
import io.github.mbackschat.a12.dm.rulekit.dsl.DateLiteral;
import io.github.mbackschat.a12.dm.rulekit.dsl.DateTimeConstruction;
import io.github.mbackschat.a12.dm.rulekit.dsl.EnumValueLiteral;
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldListPredicate;
import io.github.mbackschat.a12.dm.rulekit.dsl.Arithmetic;
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldListEntityListCondition;
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldListValueListCondition;
import io.github.mbackschat.a12.dm.rulekit.dsl.GroupScopedFieldFill;
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldPresenceCondition;
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldValue;
import io.github.mbackschat.a12.dm.rulekit.dsl.GroupListPredicate;
import io.github.mbackschat.a12.dm.rulekit.dsl.GroupPresenceCondition;
import io.github.mbackschat.a12.dm.rulekit.dsl.IndexedPresenceCondition;
import io.github.mbackschat.a12.dm.rulekit.dsl.RuleGroupPresenceCondition;
import io.github.mbackschat.a12.dm.rulekit.dsl.LengthOf;
import io.github.mbackschat.a12.dm.rulekit.dsl.ListAggregate;
import io.github.mbackschat.a12.dm.rulekit.dsl.Concatenation;
import io.github.mbackschat.a12.dm.rulekit.dsl.DateExtraction;
import io.github.mbackschat.a12.dm.rulekit.dsl.DateListAggregate;
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldValueAsNumber;
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldValueAsString;
import io.github.mbackschat.a12.dm.rulekit.dsl.FirstFilledValue;
import io.github.mbackschat.a12.dm.rulekit.dsl.RangeAsNumber;
import io.github.mbackschat.a12.dm.rulekit.dsl.RangeAsString;
import io.github.mbackschat.a12.dm.rulekit.dsl.ValueAsDate;
import io.github.mbackschat.a12.dm.rulekit.dsl.RootGroupCount;
import io.github.mbackschat.a12.dm.rulekit.dsl.Rounding;
import io.github.mbackschat.a12.dm.rulekit.dsl.LogicCondition;
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberLiteral;
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberOfValueInFields;
import io.github.mbackschat.a12.dm.rulekit.dsl.OpListMath;
import io.github.mbackschat.a12.dm.rulekit.dsl.PatternCondition;
import io.github.mbackschat.a12.dm.rulekit.dsl.RepetitionNotUniqueCondition;
import io.github.mbackschat.a12.dm.rulekit.dsl.SemanticIndexValue;
import io.github.mbackschat.a12.dm.rulekit.dsl.StringLiteral;
import io.github.mbackschat.a12.dm.rulekit.dsl.TimeConstruction;
import io.github.mbackschat.a12.dm.rulekit.dsl.TimeLiteral;
import io.github.mbackschat.a12.dm.rulekit.dsl.Today;
import io.github.mbackschat.a12.dm.rulekit.dsl.ValueListCondition;

/**
 * Walks a sealed {@link Condition} AST into the structural {@link RuleExplanation.Node} tree (F9
 * explain). It is a third render target over the same AST that {@link DslRenderer} (→ DSL text) and
 * the F10 converter walk — the exhaustive {@code switch} the sealed hierarchy enables, no
 * {@code default}.
 *
 * <p>The tree is <b>structure only</b>: each node is {@code {op, text, children}}, where {@code op}
 * backlinks into the {@code operators} glossary (meanings/gotchas live there once, not per node) and
 * {@code text} is the rendered DSL of the subtree. A {@code LogicCondition} becomes an internal node
 * whose children are its two operands; a comparison becomes a node whose children are its
 * operator-bearing operands (functions/aggregates). Value operands — fields and literals — are
 * <em>not</em> nodes; they live in {@code text}. So the tree carries exactly the operator nesting an
 * agent needs to reason about ordering, with leaves staying readable. (Per-operand expansion of
 * field-list {@code Having} filters is a later refinement; they remain in {@code text} today.)
 */
final class ExplanationTreeBuilder {

    private final DslRenderer renderer;

    ExplanationTreeBuilder(DslRenderer renderer) {
        this.renderer = renderer;
    }

    RuleExplanation.Node build(Condition condition) {
        List<RuleExplanation.Node> children = switch (condition) {
            case LogicCondition l -> List.of(build(l.left()), build(l.right()));
            case ComparisonCondition c -> concat(operand(c.left()), operand(c.right()));
            case FieldPresenceCondition f -> List.of();
            case FieldListPredicate p -> List.of();
            case GroupPresenceCondition g -> List.of();
            case RuleGroupPresenceCondition r -> List.of();
            case IndexedPresenceCondition p -> List.of();
            case GroupListPredicate p -> List.of();
            case PatternCondition p -> List.of();
            case ValueListCondition v -> List.of();
            case FieldListValueListCondition v -> List.of();
            case FieldListEntityListCondition v -> List.of();
            case GroupScopedFieldFill v -> List.of();
            case RepetitionNotUniqueCondition r -> List.of();
            case CustomConditionRef c -> List.of();
            case DateValidity v -> operand(v.dateConstruct());
            case CustomTypeValidity v -> List.of();
        };
        return new RuleExplanation.Node(condition.operatorId(), renderer.render(condition), children);
    }

    /** 0 or 1 child node for a comparison operand: a node for an operator-bearing value, nothing for a plain field/literal. */
    private List<RuleExplanation.Node> operand(Calculation calc) {
        return switch (calc) {
            case FieldValue v -> List.of();
            case NumberLiteral n -> List.of();
            case StringLiteral s -> List.of();
            case DateLiteral d -> List.of();
            case TimeLiteral t -> List.of();
            case EnumValueLiteral e -> List.of();
            case BoolLiteral b -> List.of();
            case LengthOf l -> List.of(leaf(l.operatorId(), l));
            case EnumCategoryAccess c -> List.of(leaf(c.operatorId(), c));
            case CountAggregate a -> List.of(leaf(a.operatorId(), a));
            case ListAggregate a -> List.of(leaf(a.operatorId(), a));
            case CurrentRepetition r -> List.of(leaf(r.operatorId(), r));
            case SemanticIndexValue v -> List.of(leaf(v.operatorId(), v));
            case io.github.mbackschat.a12.dm.rulekit.dsl.SemanticIndexFieldValue v -> List.of(leaf(v.operatorId(), v));
            case Today t -> List.of(leaf(t.operatorId(), t));
            case Now n -> List.of(leaf(n.operatorId(), n));
            case BaseYear b -> List.of(leaf(b.operatorId(), b));
            // date arithmetic nests further dates (Today, fields, other shifts) — recurse into them
            case DateDifference d -> List.of(new RuleExplanation.Node(d.operatorId(), renderer.render(d),
                    concat(operand(d.left()), operand(d.right()))));
            case DateShift s -> List.of(new RuleExplanation.Node(s.operatorId(), renderer.render(s),
                    concat(operand(s.date()), operand(s.amount()))));
            case Arithmetic a -> List.of(new RuleExplanation.Node(a.op().symbol(), renderer.render(a),
                    concat(operand(a.left()), operand(a.right()))));
            // rounding nests the expression it rounds — recurse into it
            case Rounding r -> List.of(new RuleExplanation.Node(r.operatorId(), renderer.render(r),
                    operand(r.operand())));
            // concatenation has no operator keyword (the + is intrinsic) — flatten its parts' nodes
            case Concatenation c -> c.parts().stream().flatMap(p -> operand(p).stream()).toList();
            case DateListAggregate a -> List.of(leaf(a.operatorId(), a));
            case FirstFilledValue a -> List.of(leaf(a.operatorId(), a));
            case FieldValueAsString c -> List.of(leaf(c.operatorId(), c));
            case RangeAsString c -> List.of(leaf(c.operatorId(), c));
            case FieldValueAsNumber c -> List.of(leaf(c.operatorId(), c));
            case RangeAsNumber c -> List.of(leaf(c.operatorId(), c));
            case OpListMath m -> List.of(new RuleExplanation.Node(m.operatorId(), renderer.render(m),
                    m.operands().stream().flatMap(o -> operand(o).stream()).toList()));
            case NumberOfValueInFields n -> List.of(leaf(n.operatorId(), n));
            case ValueAsDate v -> List.of(leaf(v.operatorId(), v));
            // date/time extraction nests its source
            case DateExtraction e -> List.of(new RuleExplanation.Node(e.operatorId(), renderer.render(e),
                    operand(e.source())));
            case DateTimePart e -> List.of(new RuleExplanation.Node(e.operatorId(), renderer.render(e),
                    operand(e.source())));
            case SumOfProducts s -> List.of(leaf(s.operatorId(), s));
            case FieldRounding r -> List.of(leaf(r.operatorId(), r));
            case DateRangeBound b -> List.of(new RuleExplanation.Node(b.operatorId(), renderer.render(b),
                    operand(b.source())));
            case DateConstruction dc -> List.of(new RuleExplanation.Node(dc.operatorId(), renderer.render(dc),
                    concat(concat(operand(dc.day()), operand(dc.month())),
                            concat(operand(dc.year1()), dc.year2().map(this::operand).orElse(List.of())))));
            case RootGroupCount c -> List.of(leaf(c.operatorId(), c));
            case TimeConstruction tc -> List.of(new RuleExplanation.Node(tc.operatorId(), renderer.render(tc),
                    concat(concat(operand(tc.hour()), operand(tc.minute())), operand(tc.second()))));
            case DateTimeConstruction dtc -> List.of(new RuleExplanation.Node(dtc.operatorId(), renderer.render(dtc),
                    concat(operand(dtc.date()), operand(dtc.time()))));
            case DateRangeConstruction dr -> List.of(new RuleExplanation.Node(dr.operatorId(), renderer.render(dr),
                    concat(operand(dr.start()), operand(dr.end()))));
        };
    }

    private RuleExplanation.Node leaf(String op, Calculation calc) {
        return new RuleExplanation.Node(op, renderer.render(calc), List.of());
    }

    private static List<RuleExplanation.Node> concat(List<RuleExplanation.Node> a, List<RuleExplanation.Node> b) {
        List<RuleExplanation.Node> out = new ArrayList<>(a);
        out.addAll(b);
        return out;
    }
}
