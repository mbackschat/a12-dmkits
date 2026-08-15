package io.github.mbackschat.a12.dm.rulekit.dsl;

import io.github.mbackschat.a12.dm.core.EntityPaths;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * The field and group paths a {@link Condition} references — collected by exhaustive switch over
 * the sealed AST (cheap thanks to D14's model-resolved refs). This is the seed of F9's
 * referenced-fields projection; today it powers the {@link Rule} builder's error-field-referenced
 * pre-check (directly, or indirectly via an enclosing group reference — kernel-verified semantics).
 *
 * <p>{@link #readMode(Condition, String)} answers a second question over the same traversal: <em>how</em> one
 * path is read — plainly, or through a semantic index or enum category. The reading mode is deliberately
 * <b>not</b> part of this record's identity, because a projection changes the operation and not which
 * entities the condition depends on: {@code ConditionEdit} classifies an {@code [A]} → {@code [A -> Cat]}
 * swap as an OPERAND edit (same node count, same references, and its operator multiset covers only condition
 * nodes), and folding the mode into this record's equality would relabel it a REFERENCES edit
 * ({@code ConditionEditTest.swappingAPlainReadForACategoryProjectionIsNotAReferenceEdit}).
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
        Refs refs = new Refs();
        collect(condition, refs);
        return new ConditionReferences(refs.fields, refs.groups);
    }

    /** The references of a value expression — e.g. a computation slot's {@code operation}. */
    public static ConditionReferences of(Calculation calculation) {
        Refs refs = new Refs();
        collect(calculation, refs);
        return new ConditionReferences(refs.fields, refs.groups);
    }

    /**
     * How a field is read: not at all, plainly, or through a <b>projection</b> — an enum category
     * ({@code [f -> Cat]}) or a semantic index ({@code [f For key]}, whose <em>key</em> field counts as
     * projected too, as the kernel matches it against the calculated field).
     *
     * <p>Ordered weakest-first, so {@code compareTo} folds several readings into the strongest one. That
     * ordering is the kernel's: where a computation touches its own computed field, a plain self-read is
     * {@code MVK_ERROR_REFERENCE_TO_CALCULATED_FIELD}, a projected one is
     * {@code MVK_ERROR_SEMANTIC_INDEX_OR_CATEGORY_FOR_ERRORFIELD}, and the projected class pre-empts the
     * plain one wherever both readings occur in the computation (kernel-measured, KERNEL-FINDINGS KF182).
     */
    public enum ReadMode {
        NONE, PLAIN, PROJECTED
    }

    /** How {@code fieldPath} is read by {@code condition} — one traversal, no intermediate collections. */
    public static ReadMode readMode(Condition condition, String fieldPath) {
        Refs refs = new Refs();
        collect(condition, refs);
        return refs.readModeOf(fieldPath);
    }

    /** How {@code fieldPath} is read by a value expression — the {@link #of(Calculation)} peer. */
    public static ReadMode readMode(Calculation calculation, String fieldPath) {
        Refs refs = new Refs();
        collect(calculation, refs);
        return refs.readModeOf(fieldPath);
    }

    /**
     * The channels one traversal fills. {@code projected} is a subset of {@code fields}: a projected read
     * is still a reference to its underlying field.
     */
    private static final class Refs {
        private final Set<String> fields = new LinkedHashSet<>();
        private final Set<String> groups = new LinkedHashSet<>();
        private final Set<String> projected = new LinkedHashSet<>();

        void field(String path) {
            fields.add(path);
        }

        void group(String path) {
            groups.add(path);
        }

        /** A field read through a semantic index or an enum category — a reference AND a projection. */
        void projectedField(String path) {
            fields.add(path);
            projected.add(path);
        }

        ReadMode readModeOf(String fieldPath) {
            if (projected.contains(fieldPath)) {
                return ReadMode.PROJECTED;
            }
            return fields.contains(fieldPath) ? ReadMode.PLAIN : ReadMode.NONE;
        }
    }

    /** Whether {@code fieldPath} is referenced directly or lies under a referenced group. */
    public boolean covers(String fieldPath) {
        return fieldPaths.contains(fieldPath)
                || groupPaths.stream().anyMatch(g -> EntityPaths.isStrictlyInside(fieldPath, g));
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

    private static void collect(Condition condition, Refs refs) {
        switch (condition) {
            case FieldPresenceCondition f -> refs.field(f.field().path());
            case EntityListPredicate p -> p.operands().forEach(o -> collectAggregateOperand(o, refs));
            case ComparisonCondition c -> {
                collect(c.left(), refs);
                collect(c.right(), refs);
            }
            case LogicCondition l -> {
                collect(l.left(), refs);
                collect(l.right(), refs);
            }
            case GroupPresenceCondition g -> refs.group(g.group().path());
            // RuleGroup is the rule's OWN group; its path is unknown here (no rule context), so it
            // contributes no concrete reference. Rule.deferredChecks() treats it as covering the error
            // field via referencesRuleGroup(...) below (the field is always under the rule's group).
            case RuleGroupPresenceCondition ignored -> { }
            case IndexedPresenceCondition p -> collect(p.index(), refs);
            case GroupListPredicate p -> p.operands().forEach(operand -> {
                switch (operand) {
                    case FieldRef field -> refs.field(field.path());
                    case GroupListRef scope -> refs.group(scope.path());
                }
            });
            case PatternCondition p -> refs.field(p.field().path());
            case ValueListCondition v -> {
                refs.field(v.field().path());
                v.values().forEach(value -> collect(value, refs));
            }
            case FieldListValueListCondition v -> {
                v.fields().forEach(f -> collectOperand(f, refs));
                v.values().forEach(value -> collect(value, refs));
            }
            case FieldListEntityListCondition v -> {
                v.fields().forEach(f -> collectOperand(f, refs));
                v.valueFields().forEach(f -> collectOperand(f, refs));
            }
            // The @From scope DOES count as a reference — measured 2026-07-25: with @From the kernel accepts an
            // error field that only the scope contains, and without it the same shape draws
            // MVK_ERROR_FIELD_NOT_REFERENCED, so the keys' own group is NOT an implicit reference
            // (validate.laws.RepetitionNotUniqueKeyKindLawsTest). Omitting the scope produced a false advisory
            // on a kernel-legal rule; this comment previously recorded the honoring as unverified.
            case RepetitionNotUniqueCondition r -> {
                r.keys().forEach(k -> refs.field(k.path()));
                r.scope().ifPresent(s -> refs.group(s.path()));
            }
            case CustomConditionRef ignored -> { /* references nothing — host-defined */ }
            case DateValidity v -> collect(v.dateConstruct(), refs);
            case CustomTypeValidity v -> refs.field(v.field().path());
        }
    }

    private static void collect(Calculation calculation, Refs refs) {
        switch (calculation) {
            case FieldValue v -> refs.field(v.field().path());
            case LengthOf l -> refs.field(l.field().path());
            case NumberLiteral n -> { }
            case StringLiteral s -> { }
            case DateLiteral d -> { }
            case TimeLiteral t -> { }
            case EnumValueLiteral e -> { }
            case BoolLiteral b -> { }
            case EnumCategoryAccess c -> refs.projectedField(c.field().path());
            case CountAggregate a -> {
                a.fields().forEach(f -> collectAggregateOperand(f, refs));
                a.groups().forEach(g -> refs.group(g.path()));
            }
            case ListAggregate a -> a.operands().forEach(o -> collectAggregateOperand(o, refs));
            case CurrentRepetition r -> refs.group(r.group().path());
            case Today ignored -> { }
            case Now ignored -> { }
            case BaseYear ignored -> { }
            case DateDifference d -> {
                collect(d.left(), refs);
                collect(d.right(), refs);
            }
            case DateShift s -> {
                collect(s.date(), refs);
                collect(s.amount(), refs);
            }
            case SemanticIndexValue v -> refs.projectedField(v.field().path());
            case SemanticIndexFieldValue v -> {
                refs.projectedField(v.field().path());
                // The KEY counts as projected too — measured, not assumed: a computation whose operation reads
                // `[Legs/LegRef For <computedField>]` draws MVK_ERROR_SEMANTIC_INDEX_OR_CATEGORY_FOR_ERRORFIELD,
                // not the plain class, so the kernel matches the calculated field against the key as well
                // (KF182; ComputedTargetProjectedReadLawsTest).
                refs.projectedField(v.indexField().path());
            }
            case Arithmetic a -> {
                collect(a.left(), refs);
                collect(a.right(), refs);
            }
            case Rounding r -> collect(r.operand(), refs);
            case Concatenation c -> c.parts().forEach(p -> collect(p, refs));
            case DateListAggregate a -> collectAggregateOperand(a.scope(), refs);
            case FirstFilledValue a -> a.operands().forEach(o -> collectFirstFilledOperand(o, refs));
            case FieldValueAsString c -> refs.field(c.field().path());
            case IndexedFieldValueAsString c -> collect(c.index(), refs);
            case RangeAsString c -> refs.field(c.field().path());
            case FieldValueAsNumber c -> refs.field(c.field().path());
            case RangeAsNumber c -> refs.field(c.field().path());
            case OpListMath m -> m.operands().forEach(o -> collect(o, refs));
            case NumberOfValueInFields n -> {
                collect(n.value(), refs);
                n.fields().forEach(f -> collectAggregateOperand(f, refs));
            }
            case ValueAsDate v -> refs.field(v.field().path());
            case DateExtraction e -> collect(e.source(), refs);
            case DateTimePart e -> collect(e.source(), refs);
            case SumOfProducts s -> {
                collectOperand(s.left(), refs);
                collectOperand(s.right(), refs);
            }
            case DateConstruction dc -> {
                collect(dc.day(), refs);
                collect(dc.month(), refs);
                collect(dc.year1(), refs);
                dc.year2().ifPresent(y -> collect(y, refs));
            }
            case TimeConstruction tc -> {
                tc.components().forEach(component -> collect(component, refs));
            }
            case DateTimeConstruction dtc -> {
                collect(dtc.date(), refs);
                collect(dtc.time(), refs);
            }
            case FieldRounding r -> refs.field(r.field().path());
            case DateRangeBound b -> collect(b.source(), refs);
            case RootGroupCount c -> refs.group(c.rootGroup().path());
            case DateRangeConstruction dr -> {
                collect(dr.start(), refs);
                collect(dr.end(), refs);
            }
        }
    }

    /** An entity-list aggregate operand: a group scope is a GROUP reference, exactly as the count
     *  aggregate's own group list already is; everything else is a field operand. */
    private static void collectAggregateOperand(AggregateOperand op, Refs refs) {
        switch (op) {
            case FieldOperand field -> collectOperand(field, refs);
            case GroupListRef group -> refs.group(group.path());
        }
    }

    /** A list operand contributes its field path (a category projection → its underlying enum field) plus —
     *  for a filtered list — its Having filter's refs. Accepts the wider {@link ValueListOperand} so an enum
     *  category projection in a value-list quantifier is collected too. */
    private static void collectOperand(ValueListOperand op, Refs refs) {
        if (op instanceof EnumCategoryAccess) {
            refs.projectedField(op.fieldPath());
        } else if (op instanceof GroupListRef group) {
            // A GROUP scope registers as a GROUP, not as a field. Registering its path in `fieldPaths` would
            // typecheck and read plausibly, and it would make `covers` blind to every field the kernel expands
            // the group to — the exact reachability-vs-exactness confusion that produced CG13-CG18 one layer up.
            // `covers` already walks `groupPaths` for containment, so this arm gets that for free.
            refs.group(group.path());
        } else {
            refs.field(op.fieldPath());
        }
        if (op instanceof FieldListRef list) {
            list.filter().ifPresent(f -> collect(f, refs));
        }
    }

    /**
     * A {@code FirstFilledValue} operand carries its category/semantic-index modifiers on the operand
     * itself rather than in the target node, so the projection is read from {@code operand} — a plain
     * {@link #collectOperand} of its field would otherwise report the read as plain.
     */
    private static void collectFirstFilledOperand(
            FirstFilledOperand operand, Refs refs) {
        boolean projected = operand.category().isPresent() || operand.semanticIndex().isPresent();
        operand.field().ifPresent(field -> {
            if (projected) {
                refs.projectedField(field.fieldPath());
            }
            collectOperand(field, refs);
        });
        operand.group().ifPresent(group -> {
            refs.group(group.path());
            operand.expandedGroupFields().forEach(field -> refs.field(field.path()));
        });
        operand.semanticIndex().filter(FirstFilledOperand.FieldIndex.class::isInstance)
                .map(FirstFilledOperand.FieldIndex.class::cast)
                .ifPresent(index -> refs.projectedField(index.field().path()));   // the key, as above
    }
}
