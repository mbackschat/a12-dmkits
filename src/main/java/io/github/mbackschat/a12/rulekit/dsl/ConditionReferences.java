package io.github.mbackschat.a12.rulekit.dsl;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The field and group paths a {@link Condition} references — collected by exhaustive switch over
 * the sealed AST (cheap thanks to D14's model-resolved refs). This is the seed of F9's
 * referenced-fields projection; today it powers the {@link Rule} builder's error-field-referenced
 * pre-check (directly, or indirectly via an enclosing group reference — kernel-verified semantics).
 */
public record ConditionReferences(Set<String> fieldPaths, Set<String> groupPaths) {

    public ConditionReferences {
        fieldPaths = Set.copyOf(fieldPaths);
        groupPaths = Set.copyOf(groupPaths);
    }

    public static ConditionReferences of(Condition condition) {
        Set<String> fields = new LinkedHashSet<>();
        Set<String> groups = new LinkedHashSet<>();
        collect(condition, fields, groups);
        return new ConditionReferences(fields, groups);
    }

    /** The references of a value expression — e.g. a computation slot's {@code operation}. */
    public static ConditionReferences of(Calculation calculation) {
        Set<String> fields = new LinkedHashSet<>();
        Set<String> groups = new LinkedHashSet<>();
        collect(calculation, fields, groups);
        return new ConditionReferences(fields, groups);
    }

    /** Whether {@code fieldPath} is referenced directly or lies under a referenced group. */
    public boolean covers(String fieldPath) {
        return fieldPaths.contains(fieldPath)
                || groupPaths.stream().anyMatch(g -> fieldPath.startsWith(g + "/"));
    }

    private static void collect(Condition condition, Set<String> fields, Set<String> groups) {
        switch (condition) {
            case FieldPresenceCondition f -> fields.add(f.field().path());
            case FieldListPredicate p -> p.fields().forEach(f -> collectOperand(f, fields, groups));
            case ComparisonCondition c -> {
                collect(c.left(), fields, groups);
                collect(c.right(), fields, groups);
            }
            case LogicCondition l -> {
                collect(l.left(), fields, groups);
                collect(l.right(), fields, groups);
            }
            case GroupPresenceCondition g -> groups.add(g.group().path());
            case GroupListPredicate p -> p.groups().forEach(g -> groups.add(g.path()));
            case PatternCondition p -> fields.add(p.field().path());
            case ValueListCondition v -> {
                fields.add(v.field().path());
                v.values().forEach(value -> collect(value, fields, groups));
            }
            case FieldListValueListCondition v -> {
                v.fields().forEach(f -> collectOperand(f, fields, groups));
                v.values().forEach(value -> collect(value, fields, groups));
            }
            // the @From scope deliberately does NOT count as a reference (unverified whether the
            // kernel's error-field rule honors it; keys are what the condition reads)
            case RepetitionNotUniqueCondition r -> r.keys().forEach(k -> fields.add(k.path()));
            case CustomConditionRef ignored -> { /* references nothing — host-defined */ }
        }
    }

    private static void collect(Calculation calculation, Set<String> fields, Set<String> groups) {
        switch (calculation) {
            case FieldValue v -> fields.add(v.field().path());
            case LengthOf l -> fields.add(l.field().path());
            case NumberLiteral n -> { }
            case StringLiteral s -> { }
            case EnumValueLiteral e -> { }
            case BoolLiteral b -> { }
            case EnumCategoryAccess c -> fields.add(c.field().path());
            case CountAggregate a -> {
                a.fields().forEach(f -> collectOperand(f, fields, groups));
                a.groups().forEach(g -> groups.add(g.path()));
            }
            case ListAggregate a -> a.operands().forEach(o -> collectOperand(o, fields, groups));
            case CurrentRepetition r -> groups.add(r.group().path());
        }
    }

    /** A list operand contributes its field path plus — for a filtered list — its Having filter's refs. */
    private static void collectOperand(FieldOperand op, Set<String> fields, Set<String> groups) {
        fields.add(op.fieldPath());
        if (op instanceof FieldListRef list) {
            list.filter().ifPresent(f -> collect(f, fields, groups));
        }
    }
}
