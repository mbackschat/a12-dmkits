package io.github.mbackschat.a12.dm.rulekit.dsl;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * The field and group paths a {@link Condition} references — collected by exhaustive switch over
 * the sealed AST (cheap thanks to D14's model-resolved refs). This is the seed of F9's
 * referenced-fields projection; today it powers the {@link Rule} builder's error-field-referenced
 * pre-check (directly, or indirectly via an enclosing group reference — kernel-verified semantics).
 */
public record ConditionReferences(Set<String> fieldPaths, Set<String> groupPaths) {

    public ConditionReferences {
        fieldPaths = immutableEncounterOrder(fieldPaths);
        groupPaths = immutableEncounterOrder(groupPaths);
    }

    private static Set<String> immutableEncounterOrder(Set<String> paths) {
        Objects.requireNonNull(paths, "paths");
        Set<String> copy = new LinkedHashSet<>(paths.size());
        paths.forEach(path -> copy.add(Objects.requireNonNull(path, "path")));
        return Collections.unmodifiableSet(copy);
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

    /**
     * Whether {@code fieldPath} is referenced as a <b>field</b> (exact) — <em>not</em> merely via its
     * containing group. The calculated-field-reference prohibition
     * ({@code MVK_ERROR_REFERENCE_TO_CALCULATED_FIELD}) uses this rather than {@link #covers}: guarding a
     * repeatable computed field with {@code GroupFilled}/{@code CurrentRepetition} on its <em>containing
     * group</em> is kernel-legal (the BA doc's current-repetition pattern), so a group reference must not
     * count as referencing the field.
     */
    public boolean referencesField(String fieldPath) {
        return fieldPaths.contains(fieldPath);
    }

    /**
     * Whether the condition references the rule's OWN group via the {@code RuleGroup} self-reference
     * anywhere. Since the error field must lie under the rule's group, a self-reference always covers
     * it — so {@link Rule#deferredChecks()} treats this as satisfying the error-field-referenced check
     * (the kernel does, per {@code DirectiveLawsTest}), avoiding a spurious {@code MVK_ERROR_FIELD_NOT_REFERENCED}.
     */
    public static boolean referencesRuleGroup(Condition condition) {
        return switch (condition) {
            case RuleGroupPresenceCondition ignored -> true;
            case LogicCondition l -> referencesRuleGroup(l.left()) || referencesRuleGroup(l.right());
            default -> false;
        };
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
            // RuleGroup is the rule's OWN group; its path is unknown here (no rule context), so it
            // contributes no concrete reference. Rule.deferredChecks() treats it as covering the error
            // field via referencesRuleGroup(...) below (the field is always under the rule's group).
            case RuleGroupPresenceCondition ignored -> { }
            case IndexedPresenceCondition p -> collect(p.index(), fields, groups);
            case GroupListPredicate p -> p.operands().forEach(operand -> {
                switch (operand) {
                    case FieldRef field -> fields.add(field.path());
                    case GroupListRef scope -> groups.add(scope.path());
                }
            });
            case PatternCondition p -> fields.add(p.field().path());
            case ValueListCondition v -> {
                fields.add(v.field().path());
                v.values().forEach(value -> collect(value, fields, groups));
            }
            case FieldListValueListCondition v -> {
                v.fields().forEach(f -> collectOperand(f, fields, groups));
                v.values().forEach(value -> collect(value, fields, groups));
            }
            case FieldListEntityListCondition v -> {
                v.fields().forEach(f -> collectOperand(f, fields, groups));
                v.valueFields().forEach(f -> collectOperand(f, fields, groups));
            }
            case GroupScopedFieldFill v -> v.scopes().forEach(s -> groups.add(s.group().path()));
            // The @From scope DOES count as a reference — measured 2026-07-25: with @From the kernel accepts an
            // error field that only the scope contains, and without it the same shape draws
            // MVK_ERROR_FIELD_NOT_REFERENCED, so the keys' own group is NOT an implicit reference
            // (validate.laws.RepetitionNotUniqueKeyKindLawsTest). Omitting the scope produced a false advisory
            // on a kernel-legal rule; this comment previously recorded the honoring as unverified.
            case RepetitionNotUniqueCondition r -> {
                r.keys().forEach(k -> fields.add(k.path()));
                r.scope().ifPresent(s -> groups.add(s.path()));
            }
            case CustomConditionRef ignored -> { /* references nothing — host-defined */ }
            case DateValidity v -> collect(v.dateConstruct(), fields, groups);
            case CustomTypeValidity v -> fields.add(v.field().path());
        }
    }

    private static void collect(Calculation calculation, Set<String> fields, Set<String> groups) {
        switch (calculation) {
            case FieldValue v -> fields.add(v.field().path());
            case LengthOf l -> fields.add(l.field().path());
            case NumberLiteral n -> { }
            case StringLiteral s -> { }
            case DateLiteral d -> { }
            case TimeLiteral t -> { }
            case EnumValueLiteral e -> { }
            case BoolLiteral b -> { }
            case EnumCategoryAccess c -> fields.add(c.field().path());
            case CountAggregate a -> {
                a.fields().forEach(f -> collectOperand(f, fields, groups));
                a.groups().forEach(g -> groups.add(g.path()));
            }
            case ListAggregate a -> a.operands().forEach(o -> collectOperand(o, fields, groups));
            case CurrentRepetition r -> groups.add(r.group().path());
            case Today ignored -> { }
            case Now ignored -> { }
            case BaseYear ignored -> { }
            case DateDifference d -> {
                collect(d.left(), fields, groups);
                collect(d.right(), fields, groups);
            }
            case DateShift s -> {
                collect(s.date(), fields, groups);
                collect(s.amount(), fields, groups);
            }
            case SemanticIndexValue v -> fields.add(v.field().path());
            case SemanticIndexFieldValue v -> {
                fields.add(v.field().path());
                fields.add(v.indexField().path());
            }
            case Arithmetic a -> {
                collect(a.left(), fields, groups);
                collect(a.right(), fields, groups);
            }
            case Rounding r -> collect(r.operand(), fields, groups);
            case Concatenation c -> c.parts().forEach(p -> collect(p, fields, groups));
            case DateListAggregate a -> collectOperand(a.scope(), fields, groups);
            case FirstFilledValue a -> a.operands().forEach(o -> collectOperand(o, fields, groups));
            case FieldValueAsString c -> fields.add(c.field().path());
            case IndexedFieldValueAsString c -> collect(c.index(), fields, groups);
            case RangeAsString c -> fields.add(c.field().path());
            case FieldValueAsNumber c -> fields.add(c.field().path());
            case RangeAsNumber c -> fields.add(c.field().path());
            case OpListMath m -> m.operands().forEach(o -> collect(o, fields, groups));
            case NumberOfValueInFields n -> {
                collect(n.value(), fields, groups);
                n.fields().forEach(f -> collectOperand(f, fields, groups));
            }
            case ValueAsDate v -> fields.add(v.field().path());
            case DateExtraction e -> collect(e.source(), fields, groups);
            case DateTimePart e -> collect(e.source(), fields, groups);
            case SumOfProducts s -> {
                collectOperand(s.left(), fields, groups);
                collectOperand(s.right(), fields, groups);
            }
            case DateConstruction dc -> {
                collect(dc.day(), fields, groups);
                collect(dc.month(), fields, groups);
                collect(dc.year1(), fields, groups);
                dc.year2().ifPresent(y -> collect(y, fields, groups));
            }
            case TimeConstruction tc -> {
                tc.components().forEach(component -> collect(component, fields, groups));
            }
            case DateTimeConstruction dtc -> {
                collect(dtc.date(), fields, groups);
                collect(dtc.time(), fields, groups);
            }
            case FieldRounding r -> fields.add(r.field().path());
            case DateRangeBound b -> collect(b.source(), fields, groups);
            case RootGroupCount c -> groups.add(c.rootGroup().path());
            case DateRangeConstruction dr -> {
                collect(dr.start(), fields, groups);
                collect(dr.end(), fields, groups);
            }
        }
    }

    /** A list operand contributes its field path (a category projection → its underlying enum field) plus —
     *  for a filtered list — its Having filter's refs. Accepts the wider {@link ValueListOperand} so an enum
     *  category projection in a value-list quantifier is collected too. */
    private static void collectOperand(ValueListOperand op, Set<String> fields, Set<String> groups) {
        fields.add(op.fieldPath());
        if (op instanceof FieldListRef list) {
            list.filter().ifPresent(f -> collect(f, fields, groups));
        }
    }
}
