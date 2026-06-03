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
        collect(calculation, fields);
        return new ConditionReferences(fields, Set.of());
    }

    /** Whether {@code fieldPath} is referenced directly or lies under a referenced group. */
    public boolean covers(String fieldPath) {
        return fieldPaths.contains(fieldPath)
                || groupPaths.stream().anyMatch(g -> fieldPath.startsWith(g + "/"));
    }

    private static void collect(Condition condition, Set<String> fields, Set<String> groups) {
        switch (condition) {
            case FieldPresenceCondition f -> fields.add(f.field().path());
            case FieldListPredicate p -> p.fields().forEach(f -> fields.add(f.path()));
            case ComparisonCondition c -> {
                collect(c.left(), fields);
                collect(c.right(), fields);
            }
            case LogicCondition l -> {
                collect(l.left(), fields, groups);
                collect(l.right(), fields, groups);
            }
            case GroupPresenceCondition g -> groups.add(g.group().path());
            case GroupListPredicate p -> p.groups().forEach(g -> groups.add(g.path()));
        }
    }

    private static void collect(Calculation calculation, Set<String> fields) {
        switch (calculation) {
            case FieldValue v -> fields.add(v.field().path());
            case LengthOf l -> fields.add(l.field().path());
            case NumberLiteral n -> { }
            case StringLiteral s -> { }
            case EnumValueLiteral e -> { }
            case BoolLiteral b -> { }
            case EnumCategoryAccess c -> fields.add(c.field().path());
        }
    }
}
