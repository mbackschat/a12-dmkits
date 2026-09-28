package io.github.mbackschat.a12.dm.adapter.profile;
import io.github.mbackschat.a12.dm.rulekit.dsl.*;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The <b>shape and cost signal</b> of a {@link Condition} (or a computation's {@link Calculation}) —
 * collected by exhaustive switch over the sealed AST, the third sibling of {@link OperatorUsage} (which
 * operators) and {@link ConditionReferences} (which paths). Where those answer <em>what</em> a condition
 * uses, this answers <em>how complex</em> it is: its node count, nesting depth, boolean fan-out, an
 * operator histogram (with multiplicity, unlike {@code OperatorUsage}'s distinct set), and the
 * performance-sensitive {@link Construct}s it contains.
 *
 * <p>It is <b>domain-blind</b> by construction — it counts shapes and names kernel operators, never a
 * field name, literal, enum value, or rule text — which is exactly what makes it safe to profile a
 * licensed model and hand the numbers to a test-model author (see {@code dmtool model profile}).
 *
 * <p>The path/model-dependent complexity (how many <em>repeatable</em> group levels an aggregate crosses,
 * a cross-level walk-up distance) needs a {@code ModelIndex} and lives in the profile assembler; this
 * class stays a pure AST walk with no kernel dependency.
 */
record ConditionComplexity(int nodeCount, int depth, int maxBooleanArity,
        Map<String, Integer> operatorHistogram, Set<Construct> constructs, int outerCorrelations,
        int maxStarSegments, Set<String> collectionPaths) {

    /** Back-compat overload (no outer-correlation/star-segment counts) — defaults both to 0. */
    public ConditionComplexity(int nodeCount, int depth, int maxBooleanArity,
            Map<String, Integer> operatorHistogram, Set<Construct> constructs) {
        this(nodeCount, depth, maxBooleanArity, operatorHistogram, constructs, 0, 0, Set.of());
    }

    /** Back-compat overload (no star-segment count) — defaults {@code maxStarSegments} to 0. */
    public ConditionComplexity(int nodeCount, int depth, int maxBooleanArity,
            Map<String, Integer> operatorHistogram, Set<Construct> constructs, int outerCorrelations) {
        this(nodeCount, depth, maxBooleanArity, operatorHistogram, constructs, outerCorrelations, 0, Set.of());
    }

    public ConditionComplexity(int nodeCount, int depth, int maxBooleanArity,
            Map<String, Integer> operatorHistogram, Set<Construct> constructs, int outerCorrelations,
            int maxStarSegments) {
        this(nodeCount, depth, maxBooleanArity, operatorHistogram, constructs, outerCorrelations,
                maxStarSegments, Set.of());
    }

    /** A performance- or correctness-sensitive construct family the interpreter/kernel treats specially. */
    public enum Construct {
        /** All-rows fold: {@code Sum}/{@code Count}/{@code MaxValue}/{@code FirstFilled}/… — the O(rows) case. */
        AGGREGATE,
        /** A field- or group-list quantifier ({@code AtLeastOne}/{@code All}/{@code No}/…) — early-exit-sensitive. */
        QUANTIFIER,
        /** {@code GroupFilled}/group-scoped fill — the O(rows²)-risk group-fill path. */
        GROUP_FILL,
        /** {@code CurrentRepetition} / predecessor reference — parallel-iteration join. */
        CURRENT_REPETITION,
        /** Semantic-index value lookup ({@code [path For "value"]}) — the value→row index scan. */
        SEMANTIC_INDEX,
        /** A {@code Having} filter ({@code $}) on a list operand — per-cell filter dispatch. */
        FILTER,
        /** Uniqueness across repetitions ({@code RepetitionNotUnique}) — the hashed/O(rows²) uniqueness check. */
        UNIQUENESS,
        /** Regex pattern match. */
        PATTERN,
        /** A value-list / entity-list membership test. */
        VALUE_LIST,
        /** Date/time arithmetic or construction (the leap-year / day-rollover special cases live here). */
        DATE_MATH,
        /** Numeric/string arithmetic (the decimal-fold allocation case). */
        ARITHMETIC,
        /** A host-defined custom condition or custom field-type validity — the customization SPI. */
        CUSTOM
    }

    public ConditionComplexity {
        operatorHistogram = Map.copyOf(operatorHistogram);
        constructs = constructs.isEmpty() ? EnumSet.noneOf(Construct.class) : EnumSet.copyOf(constructs);
        collectionPaths = Set.copyOf(collectionPaths);
    }

    /** The complexity signal of a rule condition. */
    public static ConditionComplexity of(Condition condition) {
        Acc acc = new Acc();
        acc.visit(condition);
        return acc.result();
    }

    /** The complexity signal of a computation's value expression. */
    public static ConditionComplexity of(Calculation calculation) {
        Acc acc = new Acc();
        acc.visit(calculation);
        return acc.result();
    }

    /** Mutable single-pass accumulator; recursion returns each node's subtree depth. */
    private static final class Acc {
        private int nodeCount;
        private int maxBooleanArity = 1;
        private final Map<String, Integer> histogram = new LinkedHashMap<>();
        private final Set<Construct> constructs = EnumSet.noneOf(Construct.class);
        private int rootDepth;
        private int outerCorrelations;         // uses of the `$` outer-iteration correlation (parallel-join, O(N²))
        private int maxStarSegments;            // MAX starred (`*`) segments in a single flattened list operand
        private final Set<String> collectionPaths = new java.util.HashSet<>();

        ConditionComplexity result() {
            return new ConditionComplexity(nodeCount, rootDepth, maxBooleanArity, histogram, constructs,
                    outerCorrelations, maxStarSegments, collectionPaths);
        }

        private void op(String id) {
            histogram.merge(id, 1, Integer::sum);
        }

        void visit(Condition condition) {
            rootDepth = depth(condition);
        }

        void visit(Calculation calculation) {
            rootDepth = depth(calculation);
        }

        private int depth(Condition condition) {
            nodeCount++;
            op(condition.operatorId());
            return switch (condition) {
                case FieldPresenceCondition ignored -> 1;
                case EntityListPredicate p -> {
                    // One record now carries both shapes (DG36). The construct stays what it was for each:
                    // a group scope reports GROUP_FILL, an explicit list QUANTIFIER — so a profile of an
                    // unchanged model is unchanged, and a MIXED list reports the group half, which is the
                    // one that tells a reader the extent is the kernel's.
                    constructs.add(p.hasGroupScope() ? Construct.GROUP_FILL : Construct.QUANTIFIER);
                    yield 1 + maxEntityOperandDepth(p.operands());
                }
                case ComparisonCondition c -> 1 + Math.max(depth(c.left()), depth(c.right()));
                case LogicCondition l -> {
                    maxBooleanArity = Math.max(maxBooleanArity, chainSize(l, l.op()));
                    yield 1 + Math.max(depth(l.left()), depth(l.right()));
                }
                case GroupPresenceCondition ignored -> {
                    constructs.add(Construct.GROUP_FILL);
                    yield 1;
                }
                case RuleGroupPresenceCondition ignored -> {
                    constructs.add(Construct.GROUP_FILL);
                    yield 1;
                }
                case IndexedPresenceCondition presence -> {
                    op(presence.index().operatorId());
                    constructs.add(Construct.SEMANTIC_INDEX);
                    yield 2;
                }
                case IndexedGroupPresenceCondition ignored -> {
                    op("SemanticIndex");
                    constructs.add(Construct.GROUP_FILL);
                    constructs.add(Construct.SEMANTIC_INDEX);
                    yield 2;
                }
                case GroupListPredicate predicate -> {
                    constructs.add(Construct.QUANTIFIER);
                    predicate.operands().forEach(operand -> {
                        // a checked-field entity is a scalar read; only repeatable group scopes scan a collection
                        if (operand instanceof GroupListRef scope
                                && (!scope.flattened().isEmpty() || scope.group().repeating())) {
                            collectionPaths.add(scope.path() + "/");
                        }
                    });
                    yield 1;
                }
                case PatternCondition pattern -> {
                    constructs.add(Construct.PATTERN);
                    yield 1 + cellDepth(pattern.field());
                }
                case ValueListCondition v -> {
                    constructs.add(Construct.VALUE_LIST);
                    yield 1 + Math.max(cellDepth(v.field()), maxCalcDepth(v.values()));
                }
                case FieldListValueListCondition v -> {
                    constructs.add(Construct.VALUE_LIST);
                    yield 1 + Math.max(maxOperandDepth(v.fields()), maxCalcDepth(v.values()));
                }
                case FieldListEntityListCondition v -> {
                    constructs.add(Construct.VALUE_LIST);
                    yield 1 + Math.max(maxOperandDepth(v.fields()), maxOperandDepth(v.valueFields()));
                }
                case RepetitionNotUniqueCondition r -> {
                    constructs.add(Construct.UNIQUENESS);
                    r.scope().ifPresent(s -> op("FromScope"));
                    yield 1;
                }
                case CustomConditionRef ignored -> {
                    constructs.add(Construct.CUSTOM);
                    yield 1;
                }
                case DateValidity v -> {
                    constructs.add(Construct.DATE_MATH);
                    yield 1 + depth(v.dateConstruct());
                }
                case CustomTypeValidity ignored -> {
                    constructs.add(Construct.CUSTOM);
                    yield 1;
                }
            };
        }

        private int depth(Calculation calculation) {
            nodeCount++;
            return switch (calculation) {
                case FieldValue ignored -> 1;
                case NumberLiteral ignored -> 1;
                case StringLiteral ignored -> 1;
                case DateLiteral ignored -> 1;
                case TimeLiteral ignored -> 1;
                case DateTimeLiteral ignored -> 1;
                case EnumValueLiteral ignored -> 1;
                case BoolLiteral b -> { op(b.operatorId()); yield 1; }
                case LengthOf l -> { op(l.operatorId()); yield 1 + cellDepth(l.field()); }
                case EnumCategoryAccess c -> { op(c.operatorId()); yield 1; }
                case CountAggregate a -> {
                    op(a.operatorId());
                    constructs.add(Construct.AGGREGATE);
                    yield 1 + maxAggregateOperandDepth(a.operands());
                }
                case ListAggregate a -> {
                    op(a.operatorId());
                    constructs.add(Construct.AGGREGATE);
                    yield 1 + maxAggregateOperandDepth(a.operands());
                }
                case CurrentRepetition r -> {
                    op(r.operatorId());
                    constructs.add(Construct.CURRENT_REPETITION);
                    if (r.outer()) {
                        outerCorrelations++;                     // CurrentRepetition($G) — the outer-iteration self-join
                    }
                    yield 1;
                }
                case Today t -> { op(t.operatorId()); constructs.add(Construct.DATE_MATH); yield 1; }
                case Now n -> { op(n.operatorId()); constructs.add(Construct.DATE_MATH); yield 1; }
                case BaseYear b -> { op(b.operatorId()); constructs.add(Construct.DATE_MATH); yield 1; }
                case DateDifference d -> {
                    op(d.operatorId());
                    constructs.add(Construct.DATE_MATH);
                    yield 1 + Math.max(depth(d.left()), depth(d.right()));
                }
                case DateShift s -> {
                    op(s.operatorId());
                    constructs.add(Construct.DATE_MATH);
                    yield 1 + Math.max(depth(s.date()), depth(s.amount()));
                }
                case SemanticIndexValue v -> { op(v.operatorId()); constructs.add(Construct.SEMANTIC_INDEX); yield 1; }
                case SemanticIndexFieldValue v -> { op(v.operatorId()); constructs.add(Construct.SEMANTIC_INDEX); yield 1; }
                case Arithmetic a -> {
                    constructs.add(Construct.ARITHMETIC);
                    yield 1 + Math.max(depth(a.left()), depth(a.right()));
                }
                case Rounding r -> {
                    op(r.operatorId());
                    constructs.add(Construct.ARITHMETIC);
                    yield 1 + depth(r.operand());
                }
                case Concatenation c -> {
                    constructs.add(Construct.ARITHMETIC);
                    yield 1 + maxCalcDepth(c.parts());
                }
                case DateListAggregate a -> {
                    op(a.operatorId());
                    constructs.add(Construct.AGGREGATE);
                    constructs.add(Construct.DATE_MATH);
                    yield 1 + maxAggregateOperandDepth(a.operands());
                }
                case FirstFilledValue a -> {
                    op(a.operatorId());
                    constructs.add(Construct.AGGREGATE);
                    yield 1 + maxFirstFilledOperandDepth(a.operands());
                }
                case FieldValueAsString c -> { op(c.operatorId()); yield 1; }
                case IndexedFieldValueAsString c -> {
                    op(c.operatorId());
                    op(c.index().operatorId());
                    constructs.add(Construct.SEMANTIC_INDEX);
                    yield 2;
                }
                case RangeAsString c -> { op(c.operatorId()); yield 1; }
                case FieldValueAsNumber c -> { op(c.operatorId()); yield 1; }
                case RangeAsNumber c -> { op(c.operatorId()); yield 1; }
                case OpListMath m -> {
                    op(m.operatorId());
                    constructs.add(Construct.ARITHMETIC);
                    yield 1 + maxCalcDepth(m.operands());
                }
                case NumberOfValueInFields n -> {
                    op(n.operatorId());
                    constructs.add(Construct.AGGREGATE);
                    yield 1 + Math.max(depth(n.value()), maxAggregateOperandDepth(n.fields()));
                }
                case ValueAsDate v -> {
                    op(v.operatorId());
                    op(v.dayKeywordId());
                    constructs.add(Construct.DATE_MATH);
                    yield 1;
                }
                case DateExtraction e -> {
                    op(e.operatorId());
                    constructs.add(Construct.DATE_MATH);
                    yield 1 + depth(e.source());
                }
                case DateTimePart e -> {
                    op(e.operatorId());
                    constructs.add(Construct.DATE_MATH);
                    yield 1 + depth(e.source());
                }
                case SumOfProducts s -> {
                    op(s.operatorId());
                    constructs.add(Construct.AGGREGATE);
                    yield 1 + Math.max(operandDepth(s.left()), operandDepth(s.right()));
                }
                case DateConstruction dc -> {
                    op(dc.operatorId());
                    constructs.add(Construct.DATE_MATH);
                    int d = Math.max(depth(dc.day()), Math.max(depth(dc.month()), depth(dc.year1())));
                    if (dc.year2().isPresent()) {
                        d = Math.max(d, depth(dc.year2().get()));
                    }
                    yield 1 + d;
                }
                case TimeConstruction tc -> {
                    op(tc.operatorId());
                    constructs.add(Construct.DATE_MATH);
                    yield 1 + tc.components().stream().mapToInt(this::depth).max().orElse(0);
                }
                case DateTimeConstruction dtc -> {
                    op(dtc.operatorId());
                    constructs.add(Construct.DATE_MATH);
                    yield 1 + Math.max(depth(dtc.date()), depth(dtc.time()));
                }
                case FieldRounding r -> { op(r.operatorId()); constructs.add(Construct.ARITHMETIC); yield 1; }
                case DateRangeBound b -> {
                    op(b.operatorId());
                    constructs.add(Construct.DATE_MATH);
                    yield 1 + depth(b.source());
                }
                case RootGroupCount c -> {
                    op(c.operatorId());
                    constructs.add(Construct.AGGREGATE);
                    collectionPaths.add(c.rootGroup().path() + "/");
                    yield 1;
                }
                case DateRangeConstruction dr -> {
                    op(dr.operatorId());
                    constructs.add(Construct.DATE_MATH);
                    yield 1 + Math.max(depth(dr.start()), depth(dr.end()));
                }
            };
        }

        /** A list operand is one node; a category projection contributes its operator; a filtered ({@code $})
         *  list also carries a Having condition subtree. Accepts the wider {@link ValueListOperand}. */
        private int operandDepth(ValueListOperand operand) {
            nodeCount++;
            if (operand instanceof IndexedFieldValue index) {
                op(index.operatorId());
                constructs.add(Construct.SEMANTIC_INDEX);
                return 1;
            }
            if (operand instanceof EnumCategoryAccess c) {
                op(c.operatorId());                              // [field -> Cat] as a value-list member
                return 1;
            }
            if (operand instanceof FieldListRef list) {
                if (list.semanticIndex().isPresent()) {
                    op("SemanticIndex");
                    constructs.add(Construct.SEMANTIC_INDEX);
                }
                collectionPaths.add(list.field().path().value());
                maxStarSegments = Math.max(maxStarSegments, list.flattened().size());
                if (list.correlated()) {
                    outerCorrelations++;                         // $Group*/field — the outer-iteration correlation
                }
                if (list.filter().isPresent()) {
                    op("Having");
                    constructs.add(Construct.FILTER);
                    return 1 + depth(list.filter().get());
                }
            }
            return 1;
        }

        private int cellDepth(FieldOperand.Cell cell) {
            return cell instanceof IndexedFieldValue index ? depth(index) : 0;
        }

        /** A group scope contributes no operand depth: it is one slot whose extent the kernel expands. */
        private int maxEntityOperandDepth(List<AggregateOperand> operands) {
            int max = 0;
            for (AggregateOperand o : operands) {
                max = Math.max(max, switch (o) {
                    case FieldOperand field -> operandDepth(field);
                    case GroupListRef ignored -> 0;
                    case IndexedGroupValue ignored -> 0;
                });
            }
            return max;
        }

        private int maxOperandDepth(List<? extends ValueListOperand> operands) {
            int max = 0;
            for (ValueListOperand o : operands) {
                max = Math.max(max, operandDepth(o));
            }
            return max;
        }

        /**
         * The entity-list aggregates' operand list, which may hold a group scope beside its fields.
         *
         * <p>A starred group scope is an iteration exactly as a flattened repetition list is, so it feeds
         * {@code collectionPaths} and {@code maxStarSegments} the same way — a profile that counted only the
         * field operands would rate {@code Sum(/Invoice/Lines*)} as though it iterated nothing.
         */
        private int maxAggregateOperandDepth(List<AggregateOperand> operands) {
            int max = 0;
            for (AggregateOperand o : operands) {
                max = Math.max(max, switch (o) {
                    case FieldOperand field -> operandDepth(field);
                    case GroupListRef group -> groupScopeDepth(group);   // no filter here: MVK_NO_GROUPS_ALLOWED
                    case IndexedGroupValue ignored -> {
                        // Never starred (an index and a wildcard can't address the same group), so no
                        // collectionPaths/maxStarSegments contribution — just the one node.
                        nodeCount++;
                        yield 1;
                    }
                });
            }
            return max;
        }

        /**
         * A group scope as one operand node. Each starred level it crosses is a scanned collection, keyed with a
         * trailing slash as the group-list quantifiers' scope and the root-group count are: SynthesisProfile reads
         * the scanned chain from the key's parent, and a starred group scans its own rows, where a field list's
         * key is the field inside them. One helper for every carrier, because the two copies it replaces had
         * drifted to different keys ({@code ConditionComplexityCountOperandTest}).
         */
        private int groupScopeDepth(GroupListRef scope) {
            nodeCount++;
            scope.flattened().forEach(starred -> collectionPaths.add(starred.path().value() + "/"));
            maxStarSegments = Math.max(maxStarSegments, scope.flattened().size());
            return 1;
        }

        private int maxFirstFilledOperandDepth(List<? extends FirstFilledOperand> operands) {
            int max = 0;
            for (FirstFilledOperand operand : operands) {
                int depth = operand.field().map(this::operandDepth)
                        .orElseGet(() -> groupScopeDepth(operand.group().orElseThrow()));
                if (operand.category().isPresent()) {
                    nodeCount++;
                    op("EnumCategory");
                    depth++;
                }
                if (operand.semanticIndex().isPresent()) {
                    nodeCount++;
                    op("SemanticIndex");
                    constructs.add(Construct.SEMANTIC_INDEX);
                    depth++;
                }
                max = Math.max(max, depth);
            }
            return max;
        }

        private int maxCalcDepth(List<? extends Calculation> calcs) {
            int max = 0;
            for (Calculation c : calcs) {
                max = Math.max(max, depth(c));
            }
            return max;
        }

        /** The number of leaf operands in the maximal same-{@code op} And/Or chain rooted at {@code l}. */
        private static int chainSize(Condition c, LogicCondition.Op op) {
            if (c instanceof LogicCondition l && l.op() == op) {
                return chainSize(l.left(), op) + chainSize(l.right(), op);
            }
            return 1;
        }
    }
}
