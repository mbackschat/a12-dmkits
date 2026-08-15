package io.github.mbackschat.a12.dm.rulekit.dsl;

import java.util.List;
import java.util.Optional;

import io.github.mbackschat.a12.dm.core.ValueKind;

/**
 * A flattened repetition list — the {@code Group*}{@code /Field} path syntax (KERNEL-SEMANTICS §9): the
 * values of {@code field} across <em>all</em> repetitions of the starred group(s). Legal only in
 * list slots (see {@link FieldOperand}). Obtain from {@link BoundModel#flatten(String)}, which
 * verifies every starred segment is a repeatable group ({@code MVK_INVALID_WILDCARD} pre-checked).
 *
 * <p>An optional {@link #having(Condition) Having} filter narrows the list to the repetitions where
 * a condition holds before an aggregate/quantifier consumes it. {@code Having} requires a starred
 * field ({@code MVK_WILDCARDS_REQUIRED} — satisfied structurally, since only {@code FieldListRef}
 * offers it).
 */
public record FieldListRef(FieldRef field, List<GroupRef> flattened, Optional<Condition> filter, boolean correlated)
        implements FieldOperand {

    public FieldListRef(FieldRef field, List<GroupRef> flattened) {
        this(field, flattened, Optional.empty(), false);
    }

    public FieldListRef(FieldRef field, List<GroupRef> flattened, Optional<Condition> filter) {
        this(field, flattened, filter, false);
    }

    public FieldListRef {
        flattened = List.copyOf(flattened);
        if (flattened.isEmpty()) {
            throw new IllegalArgumentException("a repetition list needs at least one starred group"
                    + " — for a plain field reference use the field handle");
        }
    }

    /**
     * This list as the {@code $} outer-iteration correlation ({@code $Group*}{@code /field}, D13) — the
     * value side of an entity-valued value-list inside a {@code Having} filter binds to the outer iteration.
     * The one correlation verb shared with the value refs and {@link FieldRef#outer()} (was {@code asOuter()}).
     */
    public FieldListRef outer() {
        return new FieldListRef(field, flattened, filter, true);
    }

    /**
     * Narrows this list to the repetitions where {@code filter} holds ({@code … Having <filter>}).
     * The kernel forbids certain constructs inside a filter condition (KERNEL-SEMANTICS §9): the
     * representable ones — {@code RepetitionNotUnique}, {@code CustomCondition}, and a nested
     * {@code Having} — are pre-checked here ({@code MVK_DISALLOWED_CONSTRUCT_IN_FILTER_CONDITION}); the
     * semantic index is also pre-checked with its own {@code MVK_SEMANTIC_INDEX_NOT_ALLOWED} code; parallel
     * iteration stays kernel-delegated. All four refusals are kernel-verified (the semantic-index group forms
     * by {@code SemanticIndexSlotLawsTest}, the others by {@code AggregateSlotLawsTest}).
     * catalog:Having
     */
    public FieldListRef having(Condition filter) {
        if (OperatorUsage.of(filter).contains("SemanticIndex")) {
            throw new IllegalArgumentException("a filter condition may not contain a semantic index"
                    + " (MVK_SEMANTIC_INDEX_NOT_ALLOWED)");
        }
        rejectForbiddenInFilter(filter);
        return new FieldListRef(field, flattened, Optional.of(filter), correlated);
    }

    private static void rejectForbiddenInFilter(Condition c) {
        switch (c) {
            case RepetitionNotUniqueCondition ignored -> forbidden("RepetitionNotUnique");
            case CustomConditionRef ignored -> forbidden("CustomCondition");
            case LogicCondition l -> {
                rejectForbiddenInFilter(l.left());
                rejectForbiddenInFilter(l.right());
            }
            case EntityListPredicate p -> p.operands().forEach(FieldListRef::rejectNestedHavingInOperand);
            case FieldListValueListCondition p -> p.fields().forEach(FieldListRef::rejectNestedHaving);
            case ComparisonCondition cmp -> {
                rejectNestedHavingIn(cmp.left());
                rejectNestedHavingIn(cmp.right());
            }
            default -> {
                // presence/group/pattern/value-list-single conditions carry no list operands here
            }
        }
    }

    private static void rejectNestedHaving(ValueListOperand op) {
        if (op instanceof FieldListRef list && list.filter().isPresent()) {
            forbidden("a nested Having");
        }
    }

    /** The aggregate-operand arm: a group scope carries no filter, so only its field siblings are checked. */
    private static void rejectNestedHavingInOperand(AggregateOperand op) {
        if (op instanceof FieldOperand field) {
            rejectNestedHaving(field);
        }
    }

    private static void rejectNestedHavingIn(Calculation value) {
        switch (value) {
            case CountAggregate a -> a.fields().forEach(FieldListRef::rejectNestedHavingInOperand);
            case ListAggregate a -> a.operands().forEach(FieldListRef::rejectNestedHavingInOperand);
            default -> { }
        }
    }

    private static void forbidden(String what) {
        throw new IllegalArgumentException("a filter condition may not contain " + what
                + " (MVK_DISALLOWED_CONSTRUCT_IN_FILTER_CONDITION)");
    }

    @Override
    public String renderedPath() {
        String path = field.path().value();
        StringBuilder rendered = new StringBuilder();
        int from = 0;
        for (GroupRef group : flattened) {
            int end = group.path().value().length();
            rendered.append(path, from, end).append('*');
            from = end;
        }
        rendered.append(path, from, path.length());
        return rendered.toString();
    }

    @Override
    public String fieldPath() {
        return field.path().value();
    }

    @Override
    public ValueKind kind() {
        return field.kind();
    }
}
