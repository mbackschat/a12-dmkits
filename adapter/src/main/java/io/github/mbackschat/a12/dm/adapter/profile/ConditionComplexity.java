package io.github.mbackschat.a12.dm.adapter.profile;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

import io.github.mbackschat.a12.dm.rulekit.dsl.Arithmetic;
import io.github.mbackschat.a12.dm.rulekit.dsl.BaseYear;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoolLiteral;
import io.github.mbackschat.a12.dm.rulekit.dsl.Calculation;
import io.github.mbackschat.a12.dm.rulekit.dsl.ComparisonCondition;
import io.github.mbackschat.a12.dm.rulekit.dsl.Concatenation;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.CountAggregate;
import io.github.mbackschat.a12.dm.rulekit.dsl.CurrentRepetition;
import io.github.mbackschat.a12.dm.rulekit.dsl.CustomConditionRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.CustomTypeValidity;
import io.github.mbackschat.a12.dm.rulekit.dsl.DateConstruction;
import io.github.mbackschat.a12.dm.rulekit.dsl.DateDifference;
import io.github.mbackschat.a12.dm.rulekit.dsl.DateExtraction;
import io.github.mbackschat.a12.dm.rulekit.dsl.DateListAggregate;
import io.github.mbackschat.a12.dm.rulekit.dsl.DateLiteral;
import io.github.mbackschat.a12.dm.rulekit.dsl.DateRangeBound;
import io.github.mbackschat.a12.dm.rulekit.dsl.DateRangeConstruction;
import io.github.mbackschat.a12.dm.rulekit.dsl.DateShift;
import io.github.mbackschat.a12.dm.rulekit.dsl.DateTimeConstruction;
import io.github.mbackschat.a12.dm.rulekit.dsl.DateTimePart;
import io.github.mbackschat.a12.dm.rulekit.dsl.DateValidity;
import io.github.mbackschat.a12.dm.rulekit.dsl.EnumCategoryAccess;
import io.github.mbackschat.a12.dm.rulekit.dsl.EnumValueLiteral;
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldListEntityListCondition;
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldListPredicate;
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldListRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldListValueListCondition;
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldOperand;
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldPresenceCondition;
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldRounding;
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldValue;
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldValueAsNumber;
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldValueAsString;
import io.github.mbackschat.a12.dm.rulekit.dsl.FirstFilledValue;
import io.github.mbackschat.a12.dm.rulekit.dsl.GroupListPredicate;
import io.github.mbackschat.a12.dm.rulekit.dsl.GroupListRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.GroupPresenceCondition;
import io.github.mbackschat.a12.dm.rulekit.dsl.GroupRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.GroupScopedFieldFill;
import io.github.mbackschat.a12.dm.rulekit.dsl.IndexedFieldValueAsString;
import io.github.mbackschat.a12.dm.rulekit.dsl.IndexedPresenceCondition;
import io.github.mbackschat.a12.dm.rulekit.dsl.LengthOf;
import io.github.mbackschat.a12.dm.rulekit.dsl.ListAggregate;
import io.github.mbackschat.a12.dm.rulekit.dsl.LogicCondition;
import io.github.mbackschat.a12.dm.rulekit.dsl.Now;
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberLiteral;
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberOfValueInFields;
import io.github.mbackschat.a12.dm.rulekit.dsl.OpListMath;
import io.github.mbackschat.a12.dm.rulekit.dsl.PatternCondition;
import io.github.mbackschat.a12.dm.rulekit.dsl.RangeAsNumber;
import io.github.mbackschat.a12.dm.rulekit.dsl.RangeAsString;
import io.github.mbackschat.a12.dm.rulekit.dsl.RepetitionNotUniqueCondition;
import io.github.mbackschat.a12.dm.rulekit.dsl.RootGroupCount;
import io.github.mbackschat.a12.dm.rulekit.dsl.Rounding;
import io.github.mbackschat.a12.dm.rulekit.dsl.RuleGroupPresenceCondition;
import io.github.mbackschat.a12.dm.rulekit.dsl.SemanticIndexFieldValue;
import io.github.mbackschat.a12.dm.rulekit.dsl.SemanticIndexValue;
import io.github.mbackschat.a12.dm.rulekit.dsl.StringLiteral;
import io.github.mbackschat.a12.dm.rulekit.dsl.SumOfProducts;
import io.github.mbackschat.a12.dm.rulekit.dsl.TimeConstruction;
import io.github.mbackschat.a12.dm.rulekit.dsl.TimeLiteral;
import io.github.mbackschat.a12.dm.rulekit.dsl.Today;
import io.github.mbackschat.a12.dm.rulekit.dsl.ValueAsDate;
import io.github.mbackschat.a12.dm.rulekit.dsl.ValueListCondition;

/**
 * A stack-safe, single-pass structural profile of one current rule or computation AST.
 *
 * <p>The walker records syntax and dispatch work only. Model-dependent capacities are assembled by
 * the profile layer that owns the {@code ModelIndex}; a scalar field beneath a repeatable group is
 * therefore not mistaken for a collection scan here.
 */
final class ConditionComplexity {

    enum Construct {
        AGGREGATE,
        QUANTIFIER,
        GROUP_FILL,
        CURRENT_REPETITION,
        SEMANTIC_INDEX,
        FILTER,
        UNIQUENESS,
        PATTERN,
        VALUE_LIST,
        DATE_MATH,
        ARITHMETIC,
        CUSTOM
    }

    enum OuterCorrelation {
        FIELD_VALUE,
        FIELD_OPERAND,
        FIELD_LIST,
        ENUM_CATEGORY,
        CURRENT_REPETITION
    }

    enum CollectionKind {
        FLATTENED_FIELD,
        FLATTENED_GROUP,
        FILLED_GROUP_REPETITIONS,
        ROOT_GROUP_REPETITIONS
    }

    record CorrelationOccurrence(OuterCorrelation form, String targetPath) {
        CorrelationOccurrence {
            Objects.requireNonNull(form, "form");
            targetPath = requirePath(targetPath, "correlation target");
        }
    }

    record CollectionOccurrence(
            CollectionKind kind,
            String targetPath,
            List<String> repeatableGroupPaths,
            OptionalInt filterNodeCount) {
        CollectionOccurrence {
            Objects.requireNonNull(kind, "kind");
            targetPath = requirePath(targetPath, "collection target");
            repeatableGroupPaths = List.copyOf(repeatableGroupPaths);
            repeatableGroupPaths.forEach(path -> requirePath(path, "repeatable group"));
            filterNodeCount = Objects.requireNonNull(filterNodeCount, "filterNodeCount");
            if (filterNodeCount.isPresent() && filterNodeCount.getAsInt() < 1) {
                throw new IllegalArgumentException(
                        "a present filter must contain at least one AST node");
            }
        }
    }

    record TreeShape(int nodeCount, int depth, int maxBooleanArity, int maxVariadicArity) {
        static Builder builder() {
            return new Builder();
        }

        static final class Builder {
            private int nodeCount;
            private int depth;
            private int maxBooleanArity;
            private int maxVariadicArity;

            Builder nodeCount(int value) {
                nodeCount = value;
                return this;
            }

            Builder depth(int value) {
                depth = value;
                return this;
            }

            Builder maxBooleanArity(int value) {
                maxBooleanArity = value;
                return this;
            }

            Builder maxVariadicArity(int value) {
                maxVariadicArity = value;
                return this;
            }

            TreeShape build() {
                return new TreeShape(nodeCount, depth, maxBooleanArity, maxVariadicArity);
            }
        }
    }

    record CatalogOperators(SortedMap<String, Integer> counts) {
        CatalogOperators {
            counts = immutableSorted(counts);
        }
    }

    record IntrinsicOperators(SortedMap<String, Integer> counts) {
        IntrinsicOperators {
            counts = immutableSorted(counts);
        }
    }

    record OperatorMix(CatalogOperators catalog, IntrinsicOperators intrinsic) {
        OperatorMix {
            Objects.requireNonNull(catalog, "catalog");
            Objects.requireNonNull(intrinsic, "intrinsic");
        }
    }

    record Correlations(
            Map<OuterCorrelation, Integer> counts,
            List<CorrelationOccurrence> occurrences) {
        Correlations {
            counts = immutableEnumMap(OuterCorrelation.class, counts);
            occurrences = List.copyOf(occurrences);
        }

        int total() {
            return counts.values().stream().mapToInt(Integer::intValue).sum();
        }
    }

    record CollectionAccesses(
            Map<CollectionKind, Integer> counts,
            List<CollectionOccurrence> occurrences,
            int maxStarDepth) {
        CollectionAccesses {
            counts = immutableEnumMap(CollectionKind.class, counts);
            occurrences = List.copyOf(occurrences);
        }

        int total() {
            return counts.values().stream().mapToInt(Integer::intValue).sum();
        }
    }

    record Syntax(
            TreeShape tree,
            OperatorMix operators,
            Set<Construct> constructs) {
        Syntax {
            Objects.requireNonNull(tree, "tree");
            Objects.requireNonNull(operators, "operators");
            constructs = constructs.isEmpty()
                    ? Collections.emptySet()
                    : Collections.unmodifiableSet(EnumSet.copyOf(constructs));
        }
    }

    record Evaluation(Correlations correlations, CollectionAccesses collections) {
        Evaluation {
            Objects.requireNonNull(correlations, "correlations");
            Objects.requireNonNull(collections, "collections");
        }
    }

    record Result(Syntax syntax, Evaluation evaluation) {
        Result {
            Objects.requireNonNull(syntax, "syntax");
            Objects.requireNonNull(evaluation, "evaluation");
        }

        TreeShape tree() {
            return syntax.tree();
        }

        OperatorMix operators() {
            return syntax.operators();
        }

        Set<Construct> constructs() {
            return syntax.constructs();
        }

        Correlations correlations() {
            return evaluation.correlations();
        }

        CollectionAccesses collections() {
            return evaluation.collections();
        }
    }

    private ConditionComplexity() {}

    static Result of(Condition condition) {
        Objects.requireNonNull(condition, "condition");
        return new Walker(new ConditionWork(condition, 1, null)).walk();
    }

    static Result of(Calculation calculation) {
        Objects.requireNonNull(calculation, "calculation");
        return new Walker(new CalculationWork(calculation, 1)).walk();
    }

    private static SortedMap<String, Integer> immutableSorted(Map<String, Integer> source) {
        return Collections.unmodifiableSortedMap(new TreeMap<>(source));
    }

    private static <E extends Enum<E>> Map<E, Integer> immutableEnumMap(
            Class<E> enumType, Map<E, Integer> source) {
        var copy = new EnumMap<E, Integer>(enumType);
        copy.putAll(source);
        return Collections.unmodifiableMap(copy);
    }

    private static String requirePath(String path, String role) {
        Objects.requireNonNull(path, role);
        if (path.isBlank()) {
            throw new IllegalArgumentException(role + " path must not be blank");
        }
        return path;
    }

    private sealed interface Work
            permits ConditionWork, CalculationWork, FieldOperandWork, GroupScopeWork, GroupOperandWork,
                    FilterBoundaryWork {}

    private record ConditionWork(Condition condition, int depth, BooleanChain chain) implements Work {}

    private record CalculationWork(Calculation calculation, int depth) implements Work {}

    private record FieldOperandWork(FieldOperand operand, int depth) implements Work {}

    private record GroupScopeWork(GroupListRef scope, int depth) implements Work {}

    private record GroupOperandWork(GroupRef group, int depth) implements Work {}

    private record PendingCollection(
            CollectionKind kind, String targetPath, List<String> repeatableGroupPaths) {
        private PendingCollection {
            repeatableGroupPaths = List.copyOf(repeatableGroupPaths);
        }
    }

    private record FilterBoundaryWork(PendingCollection collection, int nodesBeforeFilter)
            implements Work {}

    private static final class BooleanChain {
        private final LogicCondition.Op operator;
        private int arity;

        private BooleanChain(LogicCondition.Op operator) {
            this.operator = operator;
        }
    }

    private static final class Walker {
        private final ArrayDeque<Work> pending = new ArrayDeque<>();
        private int nodeCount;
        private int maxDepth;
        private int maxBooleanArity;
        private int maxVariadicArity;
        private final SortedMap<String, Integer> catalogOperators = new TreeMap<>();
        private final SortedMap<String, Integer> intrinsicOperators = new TreeMap<>();
        private final Set<Construct> constructs = EnumSet.noneOf(Construct.class);
        private final Map<OuterCorrelation, Integer> correlations =
                new EnumMap<>(OuterCorrelation.class);
        private final List<CorrelationOccurrence> correlationOccurrences = new ArrayList<>();
        private final Map<CollectionKind, Integer> collections =
                new EnumMap<>(CollectionKind.class);
        private final List<CollectionOccurrence> collectionOccurrences = new ArrayList<>();
        private int maxStarDepth;

        private Walker(Work root) {
            pending.push(root);
            if (root instanceof ConditionWork) {
                maxBooleanArity = 1;
            }
        }

        private Result walk() {
            while (!pending.isEmpty()) {
                switch (pending.pop()) {
                    case ConditionWork condition -> visit(condition);
                    case CalculationWork calculation -> visit(calculation);
                    case FieldOperandWork operand -> visit(operand);
                    case GroupScopeWork scope -> visit(scope);
                    case GroupOperandWork group -> visit(group);
                    case FilterBoundaryWork boundary -> visit(boundary);
                }
            }
            var tree = TreeShape.builder()
                    .nodeCount(nodeCount)
                    .depth(maxDepth)
                    .maxBooleanArity(maxBooleanArity)
                    .maxVariadicArity(maxVariadicArity)
                    .build();
            var syntax = new Syntax(
                    tree,
                    new OperatorMix(
                            new CatalogOperators(catalogOperators),
                            new IntrinsicOperators(intrinsicOperators)),
                    constructs);
            var evaluation = new Evaluation(
                    new Correlations(correlations, correlationOccurrences),
                    new CollectionAccesses(collections, collectionOccurrences, maxStarDepth));
            return new Result(syntax, evaluation);
        }

        private void visit(ConditionWork work) {
            node(work.depth());
            catalog(work.condition().operatorId());
            switch (work.condition()) {
                case FieldPresenceCondition presence -> {
                    if (presence.field().correlated()) {
                        correlation(
                                OuterCorrelation.FIELD_OPERAND, presence.field().path());
                    }
                }
                case FieldListPredicate predicate -> {
                    constructs.add(Construct.QUANTIFIER);
                    variadic(predicate.fields().size());
                    pushFieldOperands(predicate.fields(), work.depth() + 1);
                }
                case ComparisonCondition comparison -> {
                    calculation(comparison.right(), work.depth() + 1);
                    calculation(comparison.left(), work.depth() + 1);
                }
                case LogicCondition logic -> visitLogic(logic, work);
                case GroupPresenceCondition ignored -> constructs.add(Construct.GROUP_FILL);
                case RuleGroupPresenceCondition ignored -> constructs.add(Construct.GROUP_FILL);
                case IndexedPresenceCondition presence -> {
                    constructs.add(Construct.SEMANTIC_INDEX);
                    calculation(presence.index(), work.depth() + 1);
                }
                case GroupListPredicate predicate -> {
                    constructs.add(Construct.QUANTIFIER);
                    variadic(predicate.operands().size());
                    pushGroupScopes(predicate.operands(), work.depth() + 1);
                }
                case PatternCondition ignored -> constructs.add(Construct.PATTERN);
                case ValueListCondition values -> {
                    constructs.add(Construct.VALUE_LIST);
                    variadic(values.values().size() + 1);
                    pushCalculations(values.values(), work.depth() + 1);
                }
                case FieldListValueListCondition values -> {
                    constructs.add(Construct.QUANTIFIER);
                    constructs.add(Construct.VALUE_LIST);
                    variadic(values.fields().size() + values.values().size());
                    pushCalculations(values.values(), work.depth() + 1);
                    pushFieldOperands(values.fields(), work.depth() + 1);
                }
                case FieldListEntityListCondition values -> {
                    constructs.add(Construct.QUANTIFIER);
                    constructs.add(Construct.VALUE_LIST);
                    variadic(values.fields().size() + values.valueFields().size());
                    pushFieldOperands(values.valueFields(), work.depth() + 1);
                    pushFieldOperands(values.fields(), work.depth() + 1);
                }
                case GroupScopedFieldFill fill -> {
                    constructs.add(Construct.QUANTIFIER);
                    constructs.add(Construct.GROUP_FILL);
                    variadic(fill.scopes().size());
                    for (int index = fill.scopes().size() - 1; index >= 0; index--) {
                        pending.push(new GroupScopeWork(
                                fill.scopes().get(index), work.depth() + 1));
                    }
                }
                case RepetitionNotUniqueCondition uniqueness -> {
                    constructs.add(Construct.UNIQUENESS);
                    variadic(uniqueness.keys().size());
                    if (uniqueness.scope().isPresent()) {
                        catalog("FromScope");
                    }
                }
                case CustomConditionRef ignored -> constructs.add(Construct.CUSTOM);
                case DateValidity validity -> {
                    constructs.add(Construct.DATE_MATH);
                    calculation(validity.dateConstruct(), work.depth() + 1);
                }
                case CustomTypeValidity ignored -> constructs.add(Construct.CUSTOM);
            }
        }

        private void visitLogic(LogicCondition logic, ConditionWork work) {
            BooleanChain chain = work.chain();
            if (chain == null) {
                chain = new BooleanChain(logic.op());
            }
            pushLogicChild(logic.right(), logic.op(), chain, work.depth() + 1);
            pushLogicChild(logic.left(), logic.op(), chain, work.depth() + 1);
        }

        private void pushLogicChild(
                Condition child, LogicCondition.Op parentOperator, BooleanChain chain, int depth) {
            if (child instanceof LogicCondition nested && nested.op() == parentOperator) {
                pending.push(new ConditionWork(child, depth, chain));
                return;
            }
            chain.arity++;
            maxBooleanArity = Math.max(maxBooleanArity, chain.arity);
            pending.push(new ConditionWork(child, depth, null));
        }

        private void visit(CalculationWork work) {
            node(work.depth());
            switch (work.calculation()) {
                case FieldValue value -> {
                    if (value.outer()) {
                        correlation(OuterCorrelation.FIELD_VALUE, value.field().path());
                    }
                }
                case NumberLiteral ignored -> {}
                case StringLiteral ignored -> {}
                case DateLiteral ignored -> {}
                case TimeLiteral ignored -> {}
                case EnumValueLiteral ignored -> {}
                case BoolLiteral literal -> catalog(literal.operatorId());
                case LengthOf length -> catalog(length.operatorId());
                case EnumCategoryAccess category -> {
                    catalog(category.operatorId());
                    if (category.outer()) {
                        correlation(OuterCorrelation.ENUM_CATEGORY, category.field().path());
                    }
                }
                case CountAggregate aggregate -> visitCountAggregate(aggregate, work.depth());
                case ListAggregate aggregate -> {
                    catalog(aggregate.operatorId());
                    constructs.add(Construct.AGGREGATE);
                    variadic(aggregate.operands().size());
                    pushFieldOperands(aggregate.operands(), work.depth() + 1);
                }
                case CurrentRepetition repetition -> {
                    catalog(repetition.operatorId());
                    constructs.add(Construct.CURRENT_REPETITION);
                    if (repetition.outer()) {
                        correlation(
                                OuterCorrelation.CURRENT_REPETITION,
                                repetition.group().path());
                    }
                }
                case Today today -> {
                    catalog(today.operatorId());
                    constructs.add(Construct.DATE_MATH);
                }
                case Now now -> {
                    catalog(now.operatorId());
                    constructs.add(Construct.DATE_MATH);
                }
                case BaseYear baseYear -> {
                    catalog(baseYear.operatorId());
                    constructs.add(Construct.DATE_MATH);
                }
                case DateDifference difference -> {
                    catalog(difference.operatorId());
                    constructs.add(Construct.DATE_MATH);
                    calculation(difference.right(), work.depth() + 1);
                    calculation(difference.left(), work.depth() + 1);
                }
                case DateShift shift -> {
                    catalog(shift.operatorId());
                    constructs.add(Construct.DATE_MATH);
                    calculation(shift.amount(), work.depth() + 1);
                    calculation(shift.date(), work.depth() + 1);
                }
                case SemanticIndexValue index -> semanticIndex(index.operatorId());
                case SemanticIndexFieldValue index -> semanticIndex(index.operatorId());
                case Arithmetic arithmetic -> {
                    intrinsic(arithmetic.op().symbol());
                    constructs.add(Construct.ARITHMETIC);
                    calculation(arithmetic.right(), work.depth() + 1);
                    calculation(arithmetic.left(), work.depth() + 1);
                }
                case Rounding rounding -> {
                    catalog(rounding.operatorId());
                    constructs.add(Construct.ARITHMETIC);
                    calculation(rounding.operand(), work.depth() + 1);
                }
                case Concatenation concatenation -> {
                    intrinsic("+");
                    constructs.add(Construct.ARITHMETIC);
                    variadic(concatenation.parts().size());
                    pushCalculations(concatenation.parts(), work.depth() + 1);
                }
                case DateListAggregate aggregate -> {
                    catalog(aggregate.operatorId());
                    constructs.add(Construct.AGGREGATE);
                    constructs.add(Construct.DATE_MATH);
                    fieldOperand(aggregate.scope(), work.depth() + 1);
                }
                case FirstFilledValue first -> {
                    catalog(first.operatorId());
                    constructs.add(Construct.AGGREGATE);
                    variadic(first.operands().size());
                    pushFieldOperands(first.operands(), work.depth() + 1);
                }
                case FieldValueAsString conversion -> catalog(conversion.operatorId());
                case IndexedFieldValueAsString conversion -> {
                    catalog(conversion.operatorId());
                    calculation(conversion.index(), work.depth() + 1);
                }
                case RangeAsString conversion -> catalog(conversion.operatorId());
                case FieldValueAsNumber conversion -> catalog(conversion.operatorId());
                case RangeAsNumber conversion -> catalog(conversion.operatorId());
                case OpListMath operation -> {
                    catalog(operation.operatorId());
                    constructs.add(Construct.ARITHMETIC);
                    variadic(operation.operands().size());
                    pushCalculations(operation.operands(), work.depth() + 1);
                }
                case NumberOfValueInFields count -> {
                    catalog(count.operatorId());
                    constructs.add(Construct.AGGREGATE);
                    variadic(count.fields().size() + 1);
                    pushFieldOperands(count.fields(), work.depth() + 1);
                    calculation(count.value(), work.depth() + 1);
                }
                case ValueAsDate value -> {
                    catalog(value.operatorId());
                    catalog(value.dayKeywordId());
                    constructs.add(Construct.DATE_MATH);
                }
                case DateExtraction extraction -> {
                    catalog(extraction.operatorId());
                    constructs.add(Construct.DATE_MATH);
                    calculation(extraction.source(), work.depth() + 1);
                }
                case DateTimePart part -> {
                    catalog(part.operatorId());
                    constructs.add(Construct.DATE_MATH);
                    calculation(part.source(), work.depth() + 1);
                }
                case SumOfProducts sum -> {
                    catalog(sum.operatorId());
                    constructs.add(Construct.AGGREGATE);
                    variadic(2);
                    fieldOperand(sum.right(), work.depth() + 1);
                    fieldOperand(sum.left(), work.depth() + 1);
                }
                case DateConstruction date -> {
                    catalog(date.operatorId());
                    constructs.add(Construct.DATE_MATH);
                    variadic(date.year2().isPresent() ? 4 : 3);
                    date.year2().ifPresent(value -> calculation(value, work.depth() + 1));
                    calculation(date.year1(), work.depth() + 1);
                    calculation(date.month(), work.depth() + 1);
                    calculation(date.day(), work.depth() + 1);
                }
                case TimeConstruction time -> {
                    catalog(time.operatorId());
                    constructs.add(Construct.DATE_MATH);
                    variadic(3);
                    calculation(time.second(), work.depth() + 1);
                    calculation(time.minute(), work.depth() + 1);
                    calculation(time.hour(), work.depth() + 1);
                }
                case DateTimeConstruction dateTime -> {
                    catalog(dateTime.operatorId());
                    constructs.add(Construct.DATE_MATH);
                    calculation(dateTime.time(), work.depth() + 1);
                    calculation(dateTime.date(), work.depth() + 1);
                }
                case FieldRounding rounding -> {
                    catalog(rounding.operatorId());
                    constructs.add(Construct.ARITHMETIC);
                }
                case DateRangeBound bound -> {
                    catalog(bound.operatorId());
                    constructs.add(Construct.DATE_MATH);
                    calculation(bound.source(), work.depth() + 1);
                }
                case RootGroupCount count -> {
                    catalog(count.operatorId());
                    constructs.add(Construct.AGGREGATE);
                    collection(
                            CollectionKind.ROOT_GROUP_REPETITIONS,
                            count.rootGroup().path(),
                            List.of(count.rootGroup().path()),
                            OptionalInt.empty());
                    pending.push(new GroupOperandWork(count.rootGroup(), work.depth() + 1));
                }
                case DateRangeConstruction range -> {
                    catalog(range.operatorId());
                    constructs.add(Construct.DATE_MATH);
                    calculation(range.end(), work.depth() + 1);
                    calculation(range.start(), work.depth() + 1);
                }
            }
        }

        private void visitCountAggregate(CountAggregate aggregate, int depth) {
            catalog(aggregate.operatorId());
            constructs.add(Construct.AGGREGATE);
            variadic(aggregate.fields().size() + aggregate.groups().size());
            if (aggregate.op() == CountAggregate.CountOp.FILLED_GROUPS
                    && aggregate.fields().isEmpty()
                    && aggregate.groups().size() == 1
                    && aggregate.groups().getFirst().repeating()) {
                var group = aggregate.groups().getFirst();
                collection(
                        CollectionKind.FILLED_GROUP_REPETITIONS,
                        group.path(),
                        List.of(group.path()),
                        OptionalInt.empty());
            }
            pushGroups(aggregate.groups(), depth + 1);
            pushFieldOperands(aggregate.fields(), depth + 1);
        }

        private void semanticIndex(String operatorId) {
            catalog(operatorId);
            constructs.add(Construct.SEMANTIC_INDEX);
        }

        private void visit(FieldOperandWork work) {
            node(work.depth());
            switch (work.operand()) {
                case FieldRef field -> {
                    if (field.correlated()) {
                        correlation(OuterCorrelation.FIELD_OPERAND, field.path());
                    }
                }
                case FieldListRef list -> {
                    if (list.correlated()) {
                        correlation(OuterCorrelation.FIELD_LIST, list.field().path());
                    }
                    var collection = new PendingCollection(
                            CollectionKind.FLATTENED_FIELD,
                            list.field().path(),
                            groupPaths(list.flattened()));
                    collectionSummary(collection.kind(), collection.repeatableGroupPaths().size());
                    if (list.filter().isPresent()) {
                        catalog("Having");
                        constructs.add(Construct.FILTER);
                        pending.push(new FilterBoundaryWork(collection, nodeCount));
                        pending.push(new ConditionWork(
                                list.filter().orElseThrow(), work.depth() + 1, null));
                    } else {
                        collectionOccurrence(collection, OptionalInt.empty());
                    }
                }
            }
        }

        private void visit(GroupScopeWork work) {
            node(work.depth());
            if (!work.scope().flattened().isEmpty()) {
                collection(
                        CollectionKind.FLATTENED_GROUP,
                        work.scope().group().path(),
                        groupPaths(work.scope().flattened()),
                        OptionalInt.empty());
            }
        }

        private void visit(GroupOperandWork work) {
            node(work.depth());
        }

        private void visit(FilterBoundaryWork work) {
            collectionOccurrence(
                    work.collection(), OptionalInt.of(nodeCount - work.nodesBeforeFilter()));
        }

        private void node(int depth) {
            nodeCount++;
            maxDepth = Math.max(maxDepth, depth);
        }

        private void variadic(int arity) {
            maxVariadicArity = Math.max(maxVariadicArity, arity);
        }

        private void catalog(String operatorId) {
            catalogOperators.merge(operatorId, 1, Integer::sum);
        }

        private void intrinsic(String operatorId) {
            intrinsicOperators.merge(operatorId, 1, Integer::sum);
        }

        private void correlation(OuterCorrelation correlation, String targetPath) {
            correlations.merge(correlation, 1, Integer::sum);
            correlationOccurrences.add(new CorrelationOccurrence(correlation, targetPath));
        }

        private void collection(
                CollectionKind kind,
                String targetPath,
                List<String> repeatableGroupPaths,
                OptionalInt filterNodeCount) {
            var occurrence = new PendingCollection(kind, targetPath, repeatableGroupPaths);
            collectionSummary(kind, repeatableGroupPaths.size());
            collectionOccurrence(occurrence, filterNodeCount);
        }

        private void collectionSummary(CollectionKind kind, int starDepth) {
            collections.merge(kind, 1, Integer::sum);
            maxStarDepth = Math.max(maxStarDepth, starDepth);
        }

        private void collectionOccurrence(
                PendingCollection collection, OptionalInt filterNodeCount) {
            collectionOccurrences.add(new CollectionOccurrence(
                    collection.kind(),
                    collection.targetPath(),
                    collection.repeatableGroupPaths(),
                    filterNodeCount));
        }

        private static List<String> groupPaths(List<GroupRef> groups) {
            return groups.stream().map(GroupRef::path).toList();
        }

        private void calculation(Calculation calculation, int depth) {
            pending.push(new CalculationWork(calculation, depth));
        }

        private void fieldOperand(FieldOperand operand, int depth) {
            pending.push(new FieldOperandWork(operand, depth));
        }

        private void pushCalculations(
                List<? extends Calculation> calculations, int depth) {
            for (int index = calculations.size() - 1; index >= 0; index--) {
                calculation(calculations.get(index), depth);
            }
        }

        private void pushFieldOperands(
                List<? extends FieldOperand> operands, int depth) {
            for (int index = operands.size() - 1; index >= 0; index--) {
                fieldOperand(operands.get(index), depth);
            }
        }

        private void pushGroups(List<GroupRef> groups, int depth) {
            for (int index = groups.size() - 1; index >= 0; index--) {
                pending.push(new GroupOperandWork(groups.get(index), depth));
            }
        }

        private void pushGroupScopes(List<GroupListRef> scopes, int depth) {
            for (int index = scopes.size() - 1; index >= 0; index--) {
                pending.push(new GroupScopeWork(scopes.get(index), depth));
            }
        }
    }
}
